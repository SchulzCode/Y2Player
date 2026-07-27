#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libswresample/swresample.h>

enum y2_error_category {
    Y2_ERROR_NONE = 0,
    Y2_ERROR_SOURCE = 1,
    Y2_ERROR_UNSUPPORTED = 2,
    Y2_ERROR_CORRUPT = 3,
    Y2_ERROR_ABORTED = 4,
    Y2_ERROR_INTERNAL = 5
};

typedef struct y2_decoder {
    AVFormatContext *format;
    AVCodecContext *codec;
    AVPacket *packet;
    AVFrame *frame;
    SwrContext *resampler;
    AVStream *stream;
    int stream_index;
    int output_rate;
    int output_channels;
    int source_rate;
    int source_channels;
    int64_t duration_ms;
    int sent_end_of_stream;
    int decoder_at_end;
    int resampler_has_pending_output;
    atomic_int abort_requested;
    int error_category;
    char error_detail[256];
} y2_decoder;

static y2_decoder *decoder_from_handle(jlong handle) {
    return (y2_decoder *) (intptr_t) handle;
}

static int decoder_was_aborted(const y2_decoder *decoder) {
    return atomic_load_explicit(
        &decoder->abort_requested,
        memory_order_relaxed
    ) != 0;
}

static int decoder_interrupt_callback(void *opaque) {
    return decoder_was_aborted((const y2_decoder *) opaque);
}

/*
 * Classify the concrete FFmpeg result before consulting the abort flag.
 *
 * The flag used to win outright, so a decoder that had ever been interrupted
 * reported every later failure as ABORTED - and ABORTED maps to a transient
 * fault, so a genuinely corrupt or undecodable file was never recorded as such
 * and was offered to the user again on the next pass.
 *
 * Verdicts the interrupt callback cannot manufacture (no decoder, no demuxer,
 * no stream, invalid data) are therefore decided first and always believed.
 * AVERROR_EXIT is the interrupt callback's own result. Only after those does an
 * outstanding abort explain an otherwise unclassifiable failure, which is what
 * an interrupted read looks like when the demuxer reports its own I/O error
 * rather than propagating AVERROR_EXIT.
 */
static int error_category_for_result(const y2_decoder *decoder, int result) {
    if (result == AVERROR_DECODER_NOT_FOUND ||
        result == AVERROR_DEMUXER_NOT_FOUND ||
        result == AVERROR_STREAM_NOT_FOUND) {
        return Y2_ERROR_UNSUPPORTED;
    }
    if (result == AVERROR_INVALIDDATA) {
        return Y2_ERROR_CORRUPT;
    }
    if (result == AVERROR_EXIT) {
        return Y2_ERROR_ABORTED;
    }
    if (decoder_was_aborted(decoder)) {
        return Y2_ERROR_ABORTED;
    }
    if (result == AVERROR(ENOENT) ||
        result == AVERROR(EACCES) ||
        result == AVERROR(EIO) ||
        result == AVERROR(ETIMEDOUT)) {
        return Y2_ERROR_SOURCE;
    }
    return Y2_ERROR_INTERNAL;
}

static void decoder_clear_error(y2_decoder *decoder) {
    decoder->error_category = Y2_ERROR_NONE;
    decoder->error_detail[0] = '\0';
}

static int decoder_set_error(
    y2_decoder *decoder,
    int result,
    const char *operation
) {
    char ffmpeg_error[AV_ERROR_MAX_STRING_SIZE];
    decoder->error_category = error_category_for_result(decoder, result);
    av_make_error_string(ffmpeg_error, sizeof(ffmpeg_error), result);
    snprintf(
        decoder->error_detail,
        sizeof(decoder->error_detail),
        "%s: %s (%d)",
        operation,
        ffmpeg_error,
        result
    );
    decoder->error_detail[sizeof(decoder->error_detail) - 1] = '\0';
    return -1;
}

static void decoder_release_stream(y2_decoder *decoder) {
    swr_free(&decoder->resampler);
    av_frame_free(&decoder->frame);
    av_packet_free(&decoder->packet);
    avcodec_free_context(&decoder->codec);
    avformat_close_input(&decoder->format);
    decoder->stream = NULL;
    decoder->stream_index = -1;
    decoder->source_rate = 0;
    decoder->source_channels = 0;
    decoder->duration_ms = 0;
    decoder->sent_end_of_stream = 0;
    decoder->decoder_at_end = 0;
    decoder->resampler_has_pending_output = 0;
}

static int64_t decoder_duration_ms(const y2_decoder *decoder) {
    if (decoder->stream != NULL &&
        decoder->stream->duration != AV_NOPTS_VALUE) {
        return av_rescale_q(
            decoder->stream->duration,
            decoder->stream->time_base,
            (AVRational) { 1, 1000 }
        );
    }
    if (decoder->format != NULL && decoder->format->duration > 0) {
        return decoder->format->duration / (AV_TIME_BASE / 1000);
    }
    return 0;
}

static int decoder_open(
    y2_decoder *decoder,
    const char *path,
    int output_rate,
    int output_channels
) {
    const AVCodec *selected_codec;
    AVCodecParameters *parameters;
    AVChannelLayout output_layout = AV_CHANNEL_LAYOUT_STEREO;
    int selected_stream;
    int result;

    decoder_release_stream(decoder);
    decoder_clear_error(decoder);
    /*
     * abort_requested is deliberately NOT cleared here.
     *
     * Every decoder is opened exactly once, on a handle that native_create()
     * just calloc'd and atomic_init'd to zero, so the clear was redundant - and
     * harmful: the Kotlin side publishes the decoder reference before calling
     * open, so a cancel arriving in that window set the flag and this store
     * erased it, leaving a blocking avformat_open_input with nothing to
     * interrupt it.
     *
     * decoder_seek() still clears the flag, and must: a decoder aborted mid
     * decode has to be usable again after seeking, and the seek is itself the
     * superseding operation.
     */

    if (output_rate <= 0 || output_channels != 2) {
        return decoder_set_error(
            decoder,
            AVERROR(EINVAL),
            "unsupported output format"
        );
    }

    decoder->output_rate = output_rate;
    decoder->output_channels = output_channels;
    decoder->format = avformat_alloc_context();
    if (decoder->format == NULL) {
        return decoder_set_error(decoder, AVERROR(ENOMEM), "allocate format");
    }

    decoder->format->interrupt_callback.callback = decoder_interrupt_callback;
    decoder->format->interrupt_callback.opaque = decoder;

    /*
     * FFmpeg's defaults (5 MB / 5 s) are sized for network streams of unknown
     * type. These are local music files on eMMC or a slow SD card, and this
     * runs on the critical path of every prepare and every gapless preload, so
     * both limits are bounded to what a container header plus a little audio
     * needs. Both are still far above what MP3, FLAC, WAV, Ogg and MP4 require
     * to report a stream and a duration.
     *
     * The value to watch is a VBR MP3 with no Xing/VBRI header: its duration is
     * estimated rather than read, so if durations regress on that case this is
     * the first thing to raise.
     */
    decoder->format->probesize = 512 * 1024;
    decoder->format->max_analyze_duration = 2 * AV_TIME_BASE;

    result = avformat_open_input(&decoder->format, path, NULL, NULL);
    if (result < 0) {
        return decoder_set_error(decoder, result, "open source");
    }

    result = avformat_find_stream_info(decoder->format, NULL);
    if (result < 0) {
        return decoder_set_error(decoder, result, "read stream information");
    }

    selected_stream = av_find_best_stream(
        decoder->format,
        AVMEDIA_TYPE_AUDIO,
        -1,
        -1,
        &selected_codec,
        0
    );
    if (selected_stream < 0) {
        return decoder_set_error(decoder, selected_stream, "select audio stream");
    }

    decoder->stream_index = selected_stream;
    decoder->stream = decoder->format->streams[selected_stream];
    parameters = decoder->stream->codecpar;

    if (selected_codec == NULL) {
        selected_codec = avcodec_find_decoder(parameters->codec_id);
    }
    if (selected_codec == NULL) {
        return decoder_set_error(
            decoder,
            AVERROR_DECODER_NOT_FOUND,
            "find decoder"
        );
    }

    decoder->codec = avcodec_alloc_context3(selected_codec);
    if (decoder->codec == NULL) {
        return decoder_set_error(decoder, AVERROR(ENOMEM), "allocate decoder");
    }

    result = avcodec_parameters_to_context(decoder->codec, parameters);
    if (result < 0) {
        return decoder_set_error(decoder, result, "copy codec parameters");
    }
    decoder->codec->pkt_timebase = decoder->stream->time_base;
    decoder->codec->thread_count = 1;

    result = avcodec_open2(decoder->codec, selected_codec, NULL);
    if (result < 0) {
        return decoder_set_error(decoder, result, "open decoder");
    }

    if (decoder->codec->sample_rate <= 0 ||
        decoder->codec->ch_layout.nb_channels <= 0) {
        return decoder_set_error(
            decoder,
            AVERROR_INVALIDDATA,
            "invalid source audio format"
        );
    }

    decoder->source_rate = decoder->codec->sample_rate;
    decoder->source_channels = decoder->codec->ch_layout.nb_channels;

    result = swr_alloc_set_opts2(
        &decoder->resampler,
        &output_layout,
        AV_SAMPLE_FMT_S16,
        output_rate,
        &decoder->codec->ch_layout,
        decoder->codec->sample_fmt,
        decoder->codec->sample_rate,
        0,
        NULL
    );
    if (result < 0 || decoder->resampler == NULL) {
        return decoder_set_error(
            decoder,
            result < 0 ? result : AVERROR(ENOMEM),
            "configure resampler"
        );
    }

    result = swr_init(decoder->resampler);
    if (result < 0) {
        return decoder_set_error(decoder, result, "initialize resampler");
    }

    decoder->packet = av_packet_alloc();
    decoder->frame = av_frame_alloc();
    if (decoder->packet == NULL || decoder->frame == NULL) {
        return decoder_set_error(decoder, AVERROR(ENOMEM), "allocate decode buffers");
    }

    decoder->duration_ms = decoder_duration_ms(decoder);
    return 0;
}

static int decoder_convert_frame(
    y2_decoder *decoder,
    uint8_t *output,
    int capacity_frames
) {
    const uint8_t **input = (const uint8_t **) decoder->frame->extended_data;
    int maximum_output = swr_get_out_samples(
        decoder->resampler,
        decoder->frame->nb_samples
    );
    int converted = swr_convert(
        decoder->resampler,
        &output,
        capacity_frames,
        input,
        decoder->frame->nb_samples
    );

    av_frame_unref(decoder->frame);
    if (converted < 0) {
        return decoder_set_error(decoder, converted, "convert PCM");
    }

    decoder->resampler_has_pending_output =
        converted == capacity_frames && maximum_output > capacity_frames;
    return converted;
}

static int decoder_decode(
    y2_decoder *decoder,
    uint8_t *output,
    int capacity_frames
) {
    int result;

    if (decoder->codec == NULL || decoder->resampler == NULL) {
        return decoder_set_error(decoder, AVERROR(EINVAL), "decoder is not open");
    }
    if (decoder_was_aborted(decoder)) {
        return decoder_set_error(decoder, AVERROR_EXIT, "decode aborted");
    }

    if (decoder->resampler_has_pending_output || decoder->decoder_at_end) {
        result = swr_convert(
            decoder->resampler,
            &output,
            capacity_frames,
            NULL,
            0
        );
        if (result < 0) {
            return decoder_set_error(decoder, result, "drain resampler");
        }
        decoder->resampler_has_pending_output = result == capacity_frames;
        if (result > 0) {
            return result;
        }
        decoder->resampler_has_pending_output = 0;
        if (decoder->decoder_at_end) {
            return 0;
        }
    }

    for (;;) {
        result = avcodec_receive_frame(decoder->codec, decoder->frame);
        if (result >= 0) {
            int converted = decoder_convert_frame(
                decoder,
                output,
                capacity_frames
            );
            if (converted != 0) {
                return converted;
            }
            continue;
        }

        if (result == AVERROR_EOF) {
            decoder->decoder_at_end = 1;
            result = swr_convert(
                decoder->resampler,
                &output,
                capacity_frames,
                NULL,
                0
            );
            if (result < 0) {
                return decoder_set_error(decoder, result, "finish resampler");
            }
            return result;
        }
        if (result != AVERROR(EAGAIN)) {
            return decoder_set_error(decoder, result, "receive decoded frame");
        }

        if (decoder->sent_end_of_stream) {
            result = avcodec_send_packet(decoder->codec, NULL);
            if (result == AVERROR_EOF) {
                decoder->decoder_at_end = 1;
                continue;
            }
            if (result < 0 && result != AVERROR(EAGAIN)) {
                return decoder_set_error(decoder, result, "finish decoder");
            }
            continue;
        }

        result = av_read_frame(decoder->format, decoder->packet);
        if (result == AVERROR_EOF) {
            decoder->sent_end_of_stream = 1;
            continue;
        }
        if (result < 0) {
            return decoder_set_error(decoder, result, "read packet");
        }

        if (decoder->packet->stream_index != decoder->stream_index) {
            av_packet_unref(decoder->packet);
            continue;
        }

        result = avcodec_send_packet(decoder->codec, decoder->packet);
        av_packet_unref(decoder->packet);
        if (result < 0 && result != AVERROR(EAGAIN)) {
            return decoder_set_error(decoder, result, "submit packet");
        }
    }
}

static int decoder_seek(y2_decoder *decoder, int64_t position_ms) {
    int64_t target;
    int result;

    if (decoder->format == NULL || decoder->codec == NULL || decoder->stream == NULL) {
        return decoder_set_error(decoder, AVERROR(EINVAL), "decoder is not open");
    }

    atomic_store_explicit(&decoder->abort_requested, 0, memory_order_relaxed);
    position_ms = position_ms < 0 ? 0 : position_ms;
    target = av_rescale_q(
        position_ms,
        (AVRational) { 1, 1000 },
        decoder->stream->time_base
    );
    if (decoder->stream->start_time != AV_NOPTS_VALUE) {
        target += decoder->stream->start_time;
    }

    result = avformat_seek_file(
        decoder->format,
        decoder->stream_index,
        INT64_MIN,
        target,
        INT64_MAX,
        AVSEEK_FLAG_BACKWARD
    );
    if (result < 0) {
        return decoder_set_error(decoder, result, "seek source");
    }

    avcodec_flush_buffers(decoder->codec);
    swr_close(decoder->resampler);
    result = swr_init(decoder->resampler);
    if (result < 0) {
        return decoder_set_error(decoder, result, "reset resampler");
    }
    av_packet_unref(decoder->packet);
    av_frame_unref(decoder->frame);
    decoder->sent_end_of_stream = 0;
    decoder->decoder_at_end = 0;
    decoder->resampler_has_pending_output = 0;
    decoder_clear_error(decoder);
    return 0;
}

static jlong native_create(JNIEnv *env, jobject instance) {
    y2_decoder *decoder = calloc(1, sizeof(*decoder));
    (void) env;
    (void) instance;
    if (decoder == NULL) {
        return 0;
    }
    decoder->stream_index = -1;
    atomic_init(&decoder->abort_requested, 0);
    return (jlong) (intptr_t) decoder;
}

static jint native_open(
    JNIEnv *env,
    jobject instance,
    jlong handle,
    jstring path,
    jint output_rate,
    jint output_channels
) {
    y2_decoder *decoder = decoder_from_handle(handle);
    const char *path_utf8;
    int result;
    (void) instance;

    if (decoder == NULL || path == NULL) {
        return -1;
    }
    path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_utf8 == NULL) {
        return decoder_set_error(decoder, AVERROR(ENOMEM), "read source path");
    }
    result = decoder_open(decoder, path_utf8, output_rate, output_channels);
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    if (result < 0) {
        decoder_release_stream(decoder);
    }
    return result;
}

static jint native_decode(
    JNIEnv *env,
    jobject instance,
    jlong handle,
    jobject output_buffer,
    jint capacity_frames
) {
    y2_decoder *decoder = decoder_from_handle(handle);
    uint8_t *output;
    jlong capacity_bytes;
    (void) instance;

    if (decoder == NULL || output_buffer == NULL || capacity_frames <= 0) {
        return -1;
    }
    output = (*env)->GetDirectBufferAddress(env, output_buffer);
    capacity_bytes = (*env)->GetDirectBufferCapacity(env, output_buffer);
    if (output == NULL || capacity_bytes < (jlong) capacity_frames * 4) {
        return decoder_set_error(decoder, AVERROR(EINVAL), "invalid PCM buffer");
    }
    return decoder_decode(decoder, output, capacity_frames);
}

static jint native_seek(
    JNIEnv *env,
    jobject instance,
    jlong handle,
    jlong position_ms
) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    if (decoder == NULL) {
        return -1;
    }
    return decoder_seek(decoder, position_ms);
}

static void native_request_abort(
    JNIEnv *env,
    jobject instance,
    jlong handle
) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    if (decoder != NULL) {
        atomic_store_explicit(&decoder->abort_requested, 1, memory_order_relaxed);
    }
}

static jlong native_duration_ms(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    return decoder == NULL ? 0 : decoder->duration_ms;
}

static jint native_source_sample_rate(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    return decoder == NULL ? 0 : decoder->source_rate;
}

static jint native_source_channels(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    return decoder == NULL ? 0 : decoder->source_channels;
}

static jstring native_codec_name(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    const char *name = "";
    (void) instance;
    if (decoder != NULL && decoder->codec != NULL && decoder->codec->codec != NULL) {
        name = decoder->codec->codec->name;
    }
    return (*env)->NewStringUTF(env, name);
}

static jint native_error_category(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    return decoder == NULL ? Y2_ERROR_INTERNAL : decoder->error_category;
}

static jstring native_error_detail(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    const char *detail = decoder == NULL ? "decoder is closed" : decoder->error_detail;
    (void) instance;
    return (*env)->NewStringUTF(env, detail);
}

static void native_close(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    if (decoder == NULL) {
        return;
    }
    atomic_store_explicit(&decoder->abort_requested, 1, memory_order_relaxed);
    decoder_release_stream(decoder);
    free(decoder);
}

static jstring native_build_information(JNIEnv *env, jobject instance) {
    char information[256];
    (void) instance;
    snprintf(
        information,
        sizeof(information),
        "FFmpeg %s; avformat=%u; avcodec=%u; avutil=%u; swresample=%u; android-api=19; abi=armeabi-v7a",
        av_version_info(),
        avformat_version(),
        avcodec_version(),
        avutil_version(),
        swresample_version()
    );
    information[sizeof(information) - 1] = '\0';
    return (*env)->NewStringUTF(env, information);
}

/*
 * FFmpeg's default log callback writes to stderr, which for an Android app
 * process is discarded. Every avformat/avcodec/swresample diagnostic - the ones
 * that say *why* a file would not open - was therefore lost, leaving only the
 * single error_detail string this file assembles.
 *
 * The level stays at AV_LOG_ERROR, so this is quiet in normal use and cannot
 * turn into per-packet or per-frame logging.
 */
static void y2_log_callback(void *avcl, int level, const char *format, va_list args) {
    int priority;
    (void) avcl;
    if (level > av_log_get_level()) {
        return;
    }
    priority = level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    __android_log_vprint(priority, "y2audio", format, args);
}

static const JNINativeMethod native_audio_methods[] = {
    { "nativeCreate", "()J", (void *) native_create },
    { "nativeOpen", "(JLjava/lang/String;II)I", (void *) native_open },
    { "nativeDecode", "(JLjava/nio/ByteBuffer;I)I", (void *) native_decode },
    { "nativeSeek", "(JJ)I", (void *) native_seek },
    { "nativeRequestAbort", "(J)V", (void *) native_request_abort },
    { "nativeDurationMs", "(J)J", (void *) native_duration_ms },
    { "nativeSourceSampleRate", "(J)I", (void *) native_source_sample_rate },
    { "nativeSourceChannels", "(J)I", (void *) native_source_channels },
    { "nativeCodecName", "(J)Ljava/lang/String;", (void *) native_codec_name },
    { "nativeErrorCategory", "(J)I", (void *) native_error_category },
    { "nativeErrorDetail", "(J)Ljava/lang/String;", (void *) native_error_detail },
    { "nativeClose", "(J)V", (void *) native_close },
    { "nativeBuildInformation", "()Ljava/lang/String;", (void *) native_build_information }
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jclass native_audio_class;
    (void) reserved;

    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    native_audio_class = (*env)->FindClass(
        env,
        "com/schulzcode/y2player/playback/NativeAudio"
    );
    if (native_audio_class == NULL) {
        return JNI_ERR;
    }
    if ((*env)->RegisterNatives(
            env,
            native_audio_class,
            native_audio_methods,
            sizeof(native_audio_methods) / sizeof(native_audio_methods[0])
        ) != JNI_OK) {
        return JNI_ERR;
    }

    av_log_set_level(AV_LOG_ERROR);
    av_log_set_callback(y2_log_callback);
    return JNI_VERSION_1_6;
}
