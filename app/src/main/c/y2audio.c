#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/ucontext.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/avstring.h>
#include <libavutil/dict.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libavutil/mem.h>
#include <libavutil/replaygain.h>
#include <libavutil/time.h>
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
    int consecutive_invalid_packets;
    int invalid_packets_seen;
    int64_t decoded_output_frames;
    atomic_int abort_requested;
    int error_category;
    char error_detail[256];
} y2_decoder;

static jclass metadata_class;
static jmethodID metadata_constructor;
/*
 * avformat_find_stream_info() asks avcodec whether it may open the selected
 * codec even though the metadata probe deliberately supplies an impossible
 * allowlist. FFmpeg reports that expected rejection at AV_LOG_ERROR twice per
 * file. Keep the suppression thread-local: a real decoder error on the audio
 * thread must remain visible while a scan is running.
 */
static _Thread_local int metadata_decoder_rejection_expected;

#define Y2_METADATA_TEXT_BYTES (16 * 1024)
#define Y2_METADATA_PROBE_BYTES (32 * 1024)
#define Y2_METADATA_ANALYZE_US (100 * 1000)
#define Y2_METADATA_MAX_STREAMS 32
#define Y2_METADATA_DEADLINE_US (5 * AV_TIME_BASE)
#define Y2_MAX_CONSECUTIVE_INVALID_PACKETS 8

static atomic_int metadata_probe_bytes = ATOMIC_VAR_INIT(Y2_METADATA_PROBE_BYTES);
static atomic_int metadata_analyze_us = ATOMIC_VAR_INIT(Y2_METADATA_ANALYZE_US);

#define Y2_CRASH_PATH_BYTES 768
#define Y2_CRASH_BUFFER_BYTES 2048
#define Y2_CRASH_LOG_MAX_BYTES (64 * 1024)

enum y2_crash_operation {
    Y2_CRASH_OPERATION_NONE = 0,
    Y2_CRASH_OPERATION_METADATA = 1,
    Y2_CRASH_OPERATION_ARTWORK = 2
};

enum y2_crash_stage {
    Y2_CRASH_STAGE_IDLE = 0,
    Y2_CRASH_STAGE_GET_PATH = 1,
    Y2_CRASH_STAGE_ALLOCATE_FORMAT = 2,
    Y2_CRASH_STAGE_CONFIGURE_FORMAT = 3,
    Y2_CRASH_STAGE_OPEN_INPUT = 4,
    Y2_CRASH_STAGE_FIND_STREAM_INFO = 5,
    Y2_CRASH_STAGE_FIND_AUDIO_STREAM = 6,
    Y2_CRASH_STAGE_BUILD_JAVA_RESULT = 7,
    Y2_CRASH_STAGE_READ_ARTWORK = 8,
    Y2_CRASH_STAGE_CLOSE_PROBE = 9
};

typedef struct y2_crash_buffer {
    char bytes[Y2_CRASH_BUFFER_BYTES];
    size_t length;
} y2_crash_buffer;

static volatile sig_atomic_t y2_crash_fd = -1;
static volatile sig_atomic_t y2_crash_operation = Y2_CRASH_OPERATION_NONE;
static volatile sig_atomic_t y2_crash_stage = Y2_CRASH_STAGE_IDLE;
static volatile sig_atomic_t y2_crash_handling = 0;
static char y2_crash_path[Y2_CRASH_PATH_BYTES];
static uintptr_t y2_crash_library_base;

static const int y2_crash_signals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE };
static struct sigaction y2_previous_signal_actions[
    sizeof(y2_crash_signals) / sizeof(y2_crash_signals[0])
];

static void y2_crash_append_char(y2_crash_buffer *buffer, char value) {
    if (buffer->length < sizeof(buffer->bytes)) {
        buffer->bytes[buffer->length++] = value;
    }
}

static void y2_crash_append_text(y2_crash_buffer *buffer, const char *value) {
    size_t index = 0;
    while (value != NULL && value[index] != '\0' && buffer->length < sizeof(buffer->bytes)) {
        y2_crash_append_char(buffer, value[index++]);
    }
}

static void y2_crash_append_path(y2_crash_buffer *buffer) {
    size_t index = 0;
    while (index < sizeof(y2_crash_path) && y2_crash_path[index] != '\0') {
        unsigned char value = (unsigned char) y2_crash_path[index++];
        y2_crash_append_char(buffer, value < 0x20 || value == 0x7f ? '?' : (char) value);
    }
}

static void y2_crash_append_unsigned(y2_crash_buffer *buffer, uintptr_t value) {
    char digits[3 * sizeof(value) + 1];
    size_t count = 0;
    do {
        digits[count++] = (char) ('0' + value % 10);
        value /= 10;
    } while (value != 0 && count < sizeof(digits));
    while (count > 0) {
        y2_crash_append_char(buffer, digits[--count]);
    }
}

static void y2_crash_append_hex(y2_crash_buffer *buffer, uintptr_t value) {
    static const char hex[] = "0123456789abcdef";
    char digits[2 * sizeof(value)];
    size_t count = 0;
    y2_crash_append_text(buffer, "0x");
    do {
        digits[count++] = hex[value & 0xfu];
        value >>= 4;
    } while (value != 0 && count < sizeof(digits));
    while (count > 0) {
        y2_crash_append_char(buffer, digits[--count]);
    }
}

static void y2_crash_append_operation(y2_crash_buffer *buffer, int operation) {
    switch (operation) {
        case Y2_CRASH_OPERATION_METADATA: y2_crash_append_text(buffer, "metadata"); break;
        case Y2_CRASH_OPERATION_ARTWORK: y2_crash_append_text(buffer, "artwork"); break;
        default: y2_crash_append_text(buffer, "none"); break;
    }
}

static void y2_crash_append_stage(y2_crash_buffer *buffer, int stage) {
    switch (stage) {
        case Y2_CRASH_STAGE_GET_PATH: y2_crash_append_text(buffer, "get_path"); break;
        case Y2_CRASH_STAGE_ALLOCATE_FORMAT: y2_crash_append_text(buffer, "allocate_format"); break;
        case Y2_CRASH_STAGE_CONFIGURE_FORMAT: y2_crash_append_text(buffer, "configure_format"); break;
        case Y2_CRASH_STAGE_OPEN_INPUT: y2_crash_append_text(buffer, "open_input"); break;
        case Y2_CRASH_STAGE_FIND_STREAM_INFO: y2_crash_append_text(buffer, "find_stream_info"); break;
        case Y2_CRASH_STAGE_FIND_AUDIO_STREAM: y2_crash_append_text(buffer, "find_audio_stream"); break;
        case Y2_CRASH_STAGE_BUILD_JAVA_RESULT: y2_crash_append_text(buffer, "build_java_result"); break;
        case Y2_CRASH_STAGE_READ_ARTWORK: y2_crash_append_text(buffer, "read_artwork"); break;
        case Y2_CRASH_STAGE_CLOSE_PROBE: y2_crash_append_text(buffer, "close_probe"); break;
        default: y2_crash_append_text(buffer, "idle"); break;
    }
}

static void y2_crash_write_all(int fd, const char *bytes, size_t length) {
    size_t written = 0;
    while (written < length) {
        ssize_t result = write(fd, bytes + written, length - written);
        if (result > 0) {
            written += (size_t) result;
        } else if (result < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
}

static int y2_crash_signal_index(int signal_number) {
    size_t index;
    for (index = 0; index < sizeof(y2_crash_signals) / sizeof(y2_crash_signals[0]); index++) {
        if (y2_crash_signals[index] == signal_number) {
            return (int) index;
        }
    }
    return -1;
}

static void y2_native_crash_handler(int signal_number, siginfo_t *info, void *raw_context) {
    y2_crash_buffer buffer;
    uintptr_t program_counter = 0;
    uintptr_t link_register = 0;
    uintptr_t stack_pointer = 0;
    uintptr_t fault_address = info == NULL ? 0 : (uintptr_t) info->si_addr;
    int fd = y2_crash_fd;
    int action_index = y2_crash_signal_index(signal_number);

    if (y2_crash_handling != 0) {
        _exit(128 + signal_number);
    }
    y2_crash_handling = 1;

#if defined(__arm__)
    if (raw_context != NULL) {
        const ucontext_t *context = (const ucontext_t *) raw_context;
        program_counter = (uintptr_t) context->uc_mcontext.arm_pc;
        link_register = (uintptr_t) context->uc_mcontext.arm_lr;
        stack_pointer = (uintptr_t) context->uc_mcontext.arm_sp;
        if (fault_address == 0) {
            fault_address = (uintptr_t) context->uc_mcontext.fault_address;
        }
    }
#else
    (void) raw_context;
#endif

    buffer.length = 0;
    y2_crash_append_text(&buffer, "Y2_NATIVE_CRASH v1 signal=");
    y2_crash_append_unsigned(&buffer, (uintptr_t) signal_number);
    y2_crash_append_text(&buffer, " code=");
    y2_crash_append_unsigned(&buffer, (uintptr_t) (info == NULL ? 0 : info->si_code));
    y2_crash_append_text(&buffer, " pid=");
    y2_crash_append_unsigned(&buffer, (uintptr_t) getpid());
    y2_crash_append_text(&buffer, " tid=");
    y2_crash_append_unsigned(&buffer, (uintptr_t) syscall(__NR_gettid));
    y2_crash_append_text(&buffer, "\noperation=");
    y2_crash_append_operation(&buffer, y2_crash_operation);
    y2_crash_append_text(&buffer, " stage=");
    y2_crash_append_stage(&buffer, y2_crash_stage);
    y2_crash_append_text(&buffer, " path=");
    y2_crash_append_path(&buffer);
    y2_crash_append_text(&buffer, "\npc=");
    y2_crash_append_hex(&buffer, program_counter);
    y2_crash_append_text(&buffer, " lr=");
    y2_crash_append_hex(&buffer, link_register);
    y2_crash_append_text(&buffer, " sp=");
    y2_crash_append_hex(&buffer, stack_pointer);
    y2_crash_append_text(&buffer, " fault=");
    y2_crash_append_hex(&buffer, fault_address);
    y2_crash_append_text(&buffer, " lib_base=");
    y2_crash_append_hex(&buffer, y2_crash_library_base);
    if (program_counter >= y2_crash_library_base && y2_crash_library_base != 0) {
        y2_crash_append_text(&buffer, " pc_offset=");
        y2_crash_append_hex(&buffer, program_counter - y2_crash_library_base);
    }
    if (link_register >= y2_crash_library_base && y2_crash_library_base != 0) {
        y2_crash_append_text(&buffer, " lr_offset=");
        y2_crash_append_hex(&buffer, link_register - y2_crash_library_base);
    }
    y2_crash_append_text(&buffer, "\n---\n");

    if (fd >= 0) {
        y2_crash_write_all(fd, buffer.bytes, buffer.length);
        fsync(fd);
    }

    /* Restore Android's debuggerd handler, then let the original fault recur. */
    if (action_index >= 0) {
        sigaction(signal_number, &y2_previous_signal_actions[action_index], NULL);
    }
    y2_crash_handling = 0;
}

static void y2_crash_set_path(const char *path) {
    size_t index = 0;
    if (path != NULL) {
        while (index + 1 < sizeof(y2_crash_path) && path[index] != '\0') {
            y2_crash_path[index] = path[index];
            index++;
        }
    }
    y2_crash_path[index] = '\0';
}

static void y2_crash_begin(int operation) {
    y2_crash_path[0] = '\0';
    y2_crash_operation = operation;
    y2_crash_stage = Y2_CRASH_STAGE_GET_PATH;
}

static void y2_crash_end(void) {
    y2_crash_stage = Y2_CRASH_STAGE_IDLE;
    y2_crash_operation = Y2_CRASH_OPERATION_NONE;
    y2_crash_path[0] = '\0';
}

static jboolean native_configure_crash_reporter(JNIEnv *env, jobject instance, jstring path) {
    const char *native_path;
    struct stat status;
    struct sigaction action;
    Dl_info library;
    size_t index;
    int fd;
    int previous_fd;
    (void) instance;

    if (path == NULL) {
        return JNI_FALSE;
    }
    native_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (native_path == NULL) {
        return JNI_FALSE;
    }
    fd = open(native_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, path, native_path);
    if (fd < 0) {
        return JNI_FALSE;
    }
    if (fstat(fd, &status) == 0 && status.st_size > Y2_CRASH_LOG_MAX_BYTES) {
        ftruncate(fd, 0);
    }
    if (dladdr((const void *) y2_native_crash_handler, &library) != 0) {
        y2_crash_library_base = (uintptr_t) library.dli_fbase;
    }

    memset(&action, 0, sizeof(action));
    action.sa_sigaction = y2_native_crash_handler;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&action.sa_mask);
    for (index = 0; index < sizeof(y2_crash_signals) / sizeof(y2_crash_signals[0]); index++) {
        if (sigaction(y2_crash_signals[index], &action, &y2_previous_signal_actions[index]) != 0) {
            close(fd);
            return JNI_FALSE;
        }
    }
    previous_fd = y2_crash_fd;
    y2_crash_fd = fd;
    if (previous_fd >= 0) {
        close(previous_fd);
    }
    return JNI_TRUE;
}

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
        result == AVERROR_DEMUXER_NOT_FOUND) {
        return Y2_ERROR_UNSUPPORTED;
    }
    if (result == AVERROR_INVALIDDATA ||
        result == AVERROR_EOF ||
        result == AVERROR_STREAM_NOT_FOUND) {
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
    decoder->consecutive_invalid_packets = 0;
    decoder->invalid_packets_seen = 0;
    decoder->decoded_output_frames = 0;
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

    /*
     * Artwork is read through the dedicated metadata path, never through the
     * PCM decoder. Discarding attached-picture streams here prevents FFmpeg
     * from cloning their packets into the read queue after every seek. Apart
     * from avoiding artwork-sized allocations on playback, this keeps a bad
     * picture packet from making an otherwise valid audio seek fail.
     */
    for (unsigned int index = 0; index < decoder->format->nb_streams; index++) {
        AVStream *candidate = decoder->format->streams[index];
        if (index != (unsigned int) selected_stream &&
            (candidate->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0) {
            candidate->discard = AVDISCARD_ALL;
        }
    }

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
        AV_SAMPLE_FMT_FLT,
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
    if (converted > 0) {
        decoder->decoded_output_frames += converted;
    }
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
            decoder->consecutive_invalid_packets = 0;
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
            if (decoder->decoded_output_frames == 0 && decoder->invalid_packets_seen > 0) {
                return decoder_set_error(decoder, AVERROR_INVALIDDATA, "decode source");
            }
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
        if (result == AVERROR_INVALIDDATA) {
            decoder->invalid_packets_seen += 1;
            decoder->consecutive_invalid_packets += 1;
            /*
             * Local MP3s can contain a damaged frame or a trailing tag that
             * the demuxer exposes as audio. A decoder that already produced
             * PCM must not turn a bounded bad packet into a whole-track
             * failure at the boundary. Continue consuming input, but retain a
             * strict consecutive limit so sustained corruption still reports
             * an error instead of silently completing.
             */
            if (decoder->consecutive_invalid_packets <=
                Y2_MAX_CONSECUTIVE_INVALID_PACKETS) {
                continue;
            }
        }
        if (result < 0 && result != AVERROR(EAGAIN)) {
            return decoder_set_error(decoder, result, "submit packet");
        }
    }
}

static int64_t decoder_flac_audio_offset(y2_decoder *decoder) {
    uint8_t signature[4];
    uint8_t header[4];
    int last = 0;
    if (decoder->format == NULL || decoder->format->pb == NULL ||
        decoder->stream == NULL ||
        decoder->stream->codecpar->codec_id != AV_CODEC_ID_FLAC) {
        return -1;
    }
    if (avio_seek(decoder->format->pb, 0, SEEK_SET) < 0 ||
        avio_read(decoder->format->pb, signature, sizeof(signature)) != sizeof(signature) ||
        memcmp(signature, "fLaC", sizeof(signature)) != 0) {
        return -1;
    }
    while (!last) {
        int size;
        if (avio_read(decoder->format->pb, header, sizeof(header)) != sizeof(header)) {
            return -1;
        }
        last = (header[0] & 0x80) != 0;
        size = (header[1] << 16) | (header[2] << 8) | header[3];
        if (avio_skip(decoder->format->pb, size) < 0) return -1;
    }
    return avio_tell(decoder->format->pb);
}

static int decoder_reset_after_seek(y2_decoder *decoder) {
    int result;
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
    decoder->consecutive_invalid_packets = 0;
    decoder->invalid_packets_seen = 0;
    decoder->decoded_output_frames = 0;
    decoder_clear_error(decoder);
    return 0;
}

/*
 * Very short FLAC files without a SEEKTABLE can contain no frame boundary at
 * or after a requested timestamp. Upstream FFmpeg then returns -1 from both
 * public timestamp-seek APIs. Reposition to the first audio byte and decode
 * exactly to the target so a valid file remains accurately seekable. This is
 * a correctness fallback; indexed and binary timestamp seeks stay first.
 */
static int decoder_seek_flac_from_start(y2_decoder *decoder, int64_t position_ms) {
    int64_t audio_offset;
    int64_t frames_remaining;
    float *discard;
    int result = 0;

    if (decoder->stream == NULL ||
        decoder->stream->codecpar->codec_id != AV_CODEC_ID_FLAC) {
        return -1;
    }
    avformat_flush(decoder->format);
    audio_offset = decoder_flac_audio_offset(decoder);
    if (audio_offset < 0 || avio_seek(decoder->format->pb, audio_offset, SEEK_SET) < 0) {
        return -1;
    }
    if (decoder_reset_after_seek(decoder) < 0) return -1;
    frames_remaining = av_rescale(position_ms, decoder->output_rate, 1000);
    discard = av_malloc_array(4096, decoder->output_channels * sizeof(*discard));
    if (discard == NULL) {
        return decoder_set_error(decoder, AVERROR(ENOMEM), "allocate seek discard buffer");
    }
    while (frames_remaining > 0) {
        int requested = frames_remaining > 4096 ? 4096 : (int) frames_remaining;
        result = decoder_decode(decoder, (uint8_t *) discard, requested);
        if (result <= 0) break;
        frames_remaining -= result;
    }
    av_free(discard);
    if (result < 0) return -1;
    if (frames_remaining > 0) {
        return decoder_set_error(decoder, AVERROR_EOF, "seek beyond decoded audio");
    }
    decoder->decoded_output_frames = 0;
    decoder->invalid_packets_seen = 0;
    decoder->consecutive_invalid_packets = 0;
    decoder_clear_error(decoder);
    return 0;
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
        /*
         * FFmpeg 8.1's bounded seek rejects some valid FLAC files carrying an
         * attached picture with AVERROR(EPERM), while the older single-target
         * path succeeds on the same seekable file. Keep the precise bounded
         * path first and use the public fallback only after a real failure.
         */
        result = av_seek_frame(
            decoder->format,
            decoder->stream_index,
            target,
            AVSEEK_FLAG_BACKWARD
        );
        if (result < 0) {
            if (decoder_seek_flac_from_start(decoder, position_ms) == 0) {
                return 0;
            }
            if (decoder->error_category != Y2_ERROR_NONE) return -1;
            return decoder_set_error(decoder, result, "seek source");
        }
    }

    return decoder_reset_after_seek(decoder);
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

static int64_t decoder_output_capacity_bytes(
    const y2_decoder *decoder,
    int capacity_frames
) {
    if (decoder == NULL || decoder->output_channels <= 0 || capacity_frames <= 0) {
        return -1;
    }
    return (int64_t) capacity_frames * decoder->output_channels * sizeof(float);
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
    int64_t required_capacity_bytes;
    (void) instance;

    if (decoder == NULL || output_buffer == NULL || capacity_frames <= 0) {
        return -1;
    }
    output = (*env)->GetDirectBufferAddress(env, output_buffer);
    capacity_bytes = (*env)->GetDirectBufferCapacity(env, output_buffer);
    required_capacity_bytes = decoder_output_capacity_bytes(decoder, capacity_frames);
    if (output == NULL || required_capacity_bytes < 0 ||
        capacity_bytes < (jlong) required_capacity_bytes) {
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

static jint native_decode_warning_count(JNIEnv *env, jobject instance, jlong handle) {
    y2_decoder *decoder = decoder_from_handle(handle);
    (void) env;
    (void) instance;
    return decoder == NULL ? 0 : decoder->invalid_packets_seen;
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

/**
 * ReplayGain is exported by FFmpeg's demuxers as typed stream side data. Using
 * that representation keeps ID3, Vorbis-comment, FLAC, MP4 and APE tag parsing
 * inside FFmpeg instead of maintaining a second container parser in the app.
 */
static const AVReplayGain *parameters_replay_gain(const AVCodecParameters *parameters) {
    const AVPacketSideData *side_data;

    if (parameters == NULL) {
        return NULL;
    }
    side_data = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_REPLAYGAIN
    );
    if (side_data == NULL || side_data->size < sizeof(AVReplayGain)) {
        return NULL;
    }
    return (const AVReplayGain *) side_data->data;
}

typedef struct y2_replay_gain_values {
    int32_t track_gain;
    uint32_t track_peak;
    int32_t album_gain;
    uint32_t album_peak;
} y2_replay_gain_values;

static const char *replay_gain_tag(
    const AVStream *stream,
    const AVFormatContext *format,
    const char *key
) {
    const AVDictionaryEntry *entry = stream == NULL
        ? NULL
        : av_dict_get(stream->metadata, key, NULL, 0);
    if (entry == NULL && format != NULL) {
        entry = av_dict_get(format->metadata, key, NULL, 0);
    }
    return entry == NULL ? NULL : entry->value;
}

static int replay_gain_scaled_decimal(
    const char *value,
    int allow_db,
    int64_t minimum,
    int64_t maximum,
    int64_t *scaled
) {
    char *end;
    double parsed;
    double scaled_double;
    if (value == NULL) return 0;
    errno = 0;
    parsed = strtod(value, &end);
    if (errno != 0 || end == value || parsed != parsed) return 0;
    while (*end == ' ' || *end == '\t') end++;
    if (allow_db && (end[0] == 'd' || end[0] == 'D') &&
        (end[1] == 'b' || end[1] == 'B')) {
        end += 2;
        while (*end == ' ' || *end == '\t') end++;
    }
    /* FFmpeg joins duplicate dictionary values with ';'. First wins. */
    if (*end != '\0' && *end != ';') return 0;
    scaled_double = parsed * 100000.0;
    if (scaled_double < (double) minimum || scaled_double > (double) maximum) return 0;
    *scaled = (int64_t) (scaled_double + (scaled_double >= 0.0 ? 0.5 : -0.5));
    return 1;
}

static y2_replay_gain_values resolved_replay_gain(
    const AVStream *stream,
    const AVFormatContext *format
) {
    const AVReplayGain *side_data = stream == NULL
        ? NULL
        : parameters_replay_gain(stream->codecpar);
    y2_replay_gain_values result = {
        side_data == NULL ? INT32_MIN : side_data->track_gain,
        side_data == NULL ? 0 : side_data->track_peak,
        side_data == NULL ? INT32_MIN : side_data->album_gain,
        side_data == NULL ? 0 : side_data->album_peak
    };
    const char *value;
    int64_t scaled;

    value = replay_gain_tag(stream, format, "replaygain_track_gain");
    if (value != NULL) {
        result.track_gain = replay_gain_scaled_decimal(
            value, 1, INT32_MIN + 1LL, INT32_MAX, &scaled
        ) ? (int32_t) scaled : INT32_MIN;
    }
    value = replay_gain_tag(stream, format, "replaygain_track_peak");
    if (value != NULL) {
        result.track_peak = replay_gain_scaled_decimal(
            value, 0, 0, UINT32_MAX, &scaled
        ) ? (uint32_t) scaled : 0;
    }
    value = replay_gain_tag(stream, format, "replaygain_album_gain");
    if (value != NULL) {
        result.album_gain = replay_gain_scaled_decimal(
            value, 1, INT32_MIN + 1LL, INT32_MAX, &scaled
        ) ? (int32_t) scaled : INT32_MIN;
    }
    value = replay_gain_tag(stream, format, "replaygain_album_peak");
    if (value != NULL) {
        result.album_peak = replay_gain_scaled_decimal(
            value, 0, 0, UINT32_MAX, &scaled
        ) ? (uint32_t) scaled : 0;
    }
    return result;
}

static y2_replay_gain_values decoder_replay_gain(const y2_decoder *decoder) {
    if (decoder == NULL || decoder->stream == NULL) {
        return (y2_replay_gain_values) { INT32_MIN, 0, INT32_MIN, 0 };
    }
    return resolved_replay_gain(decoder->stream, decoder->format);
}

static jint native_replay_gain_track_gain(JNIEnv *env, jobject instance, jlong handle) {
    y2_replay_gain_values gain = decoder_replay_gain(decoder_from_handle(handle));
    (void) env;
    (void) instance;
    return gain.track_gain;
}

static jlong native_replay_gain_track_peak(JNIEnv *env, jobject instance, jlong handle) {
    y2_replay_gain_values gain = decoder_replay_gain(decoder_from_handle(handle));
    (void) env;
    (void) instance;
    return (jlong) gain.track_peak;
}

static jint native_replay_gain_album_gain(JNIEnv *env, jobject instance, jlong handle) {
    y2_replay_gain_values gain = decoder_replay_gain(decoder_from_handle(handle));
    (void) env;
    (void) instance;
    return gain.album_gain;
}

static jlong native_replay_gain_album_peak(JNIEnv *env, jobject instance, jlong handle) {
    y2_replay_gain_values gain = decoder_replay_gain(decoder_from_handle(handle));
    (void) env;
    (void) instance;
    return (jlong) gain.album_peak;
}

typedef struct y2_metadata_probe {
    AVFormatContext *format;
    AVStream *stream;
    int64_t deadline_us;
    int64_t setup_us;
    int64_t open_us;
    int64_t stream_info_us;
    int64_t select_stream_us;
    int64_t dictionary_us;
    int64_t replay_gain_us;
    int64_t artwork_us;
    unsigned int timed_phases;
    int error_category;
    char error_detail[256];
} y2_metadata_probe;

enum y2_metadata_timed_phase {
    Y2_METADATA_TIMED_SETUP = 1u << 0,
    Y2_METADATA_TIMED_OPEN = 1u << 1,
    Y2_METADATA_TIMED_STREAM_INFO = 1u << 2,
    Y2_METADATA_TIMED_SELECT_STREAM = 1u << 3,
    Y2_METADATA_TIMED_DICTIONARY = 1u << 4,
    Y2_METADATA_TIMED_REPLAY_GAIN = 1u << 5,
    Y2_METADATA_TIMED_ARTWORK = 1u << 6
};

typedef struct y2_profile_phase {
    int64_t count;
    int64_t total_us;
    int64_t maximum_us;
} y2_profile_phase;

typedef struct y2_metadata_profile {
    int64_t calls;
    int64_t successes;
    int64_t failures;
    int64_t total_us;
    int64_t maximum_us;
    int64_t bytes_read;
    int64_t maximum_bytes_read;
    y2_profile_phase phase[10];
} y2_metadata_profile;

static y2_metadata_profile metadata_profile;
static pthread_mutex_t metadata_profile_mutex = PTHREAD_MUTEX_INITIALIZER;

static void metadata_profile_add(y2_profile_phase *phase, int64_t elapsed_us) {
    phase->count += 1;
    phase->total_us += elapsed_us;
    if (elapsed_us > phase->maximum_us) {
        phase->maximum_us = elapsed_us;
    }
}

static void metadata_profile_record(
    const y2_metadata_probe *probe,
    int success,
    int64_t total_us,
    int64_t path_us,
    int64_t java_result_us,
    int64_t close_us,
    int64_t bytes_read
) {
    pthread_mutex_lock(&metadata_profile_mutex);
    metadata_profile.calls += 1;
    metadata_profile.successes += success ? 1 : 0;
    metadata_profile.failures += success ? 0 : 1;
    metadata_profile.total_us += total_us;
    metadata_profile.bytes_read += bytes_read;
    if (total_us > metadata_profile.maximum_us) metadata_profile.maximum_us = total_us;
    if (bytes_read > metadata_profile.maximum_bytes_read) metadata_profile.maximum_bytes_read = bytes_read;
    metadata_profile_add(&metadata_profile.phase[0], path_us);
    if ((probe->timed_phases & Y2_METADATA_TIMED_SETUP) != 0) {
        metadata_profile_add(&metadata_profile.phase[1], probe->setup_us);
    }
    if ((probe->timed_phases & Y2_METADATA_TIMED_OPEN) != 0) {
        metadata_profile_add(&metadata_profile.phase[2], probe->open_us);
    }
    if ((probe->timed_phases & Y2_METADATA_TIMED_STREAM_INFO) != 0) {
        metadata_profile_add(&metadata_profile.phase[3], probe->stream_info_us);
    }
    if ((probe->timed_phases & Y2_METADATA_TIMED_SELECT_STREAM) != 0) {
        metadata_profile_add(&metadata_profile.phase[4], probe->select_stream_us);
    }
    if ((probe->timed_phases & Y2_METADATA_TIMED_DICTIONARY) != 0) {
        metadata_profile_add(&metadata_profile.phase[5], probe->dictionary_us);
    }
    if ((probe->timed_phases & Y2_METADATA_TIMED_REPLAY_GAIN) != 0) {
        metadata_profile_add(&metadata_profile.phase[6], probe->replay_gain_us);
    }
    if ((probe->timed_phases & Y2_METADATA_TIMED_ARTWORK) != 0) {
        metadata_profile_add(&metadata_profile.phase[7], probe->artwork_us);
    }
    metadata_profile_add(&metadata_profile.phase[8], java_result_us);
    metadata_profile_add(&metadata_profile.phase[9], close_us);
    pthread_mutex_unlock(&metadata_profile_mutex);
}

static int metadata_interrupt_callback(void *opaque) {
    const y2_metadata_probe *probe = (const y2_metadata_probe *) opaque;
    return av_gettime_relative() >= probe->deadline_us;
}

static int metadata_error_category(int result) {
    if (result == AVERROR_DECODER_NOT_FOUND ||
        result == AVERROR_DEMUXER_NOT_FOUND) {
        return Y2_ERROR_UNSUPPORTED;
    }
    if (result == AVERROR_INVALIDDATA ||
        result == AVERROR_EOF ||
        result == AVERROR_STREAM_NOT_FOUND) {
        return Y2_ERROR_CORRUPT;
    }
    if (result == AVERROR(ENOENT) ||
        result == AVERROR(EACCES) ||
        result == AVERROR(EIO) ||
        result == AVERROR(ETIMEDOUT) ||
        result == AVERROR_EXIT) {
        return Y2_ERROR_SOURCE;
    }
    return Y2_ERROR_INTERNAL;
}

/*
 * The ADTS demuxer normally asks an AAC decoder to fill these two fields.
 * Metadata scans intentionally forbid decoder opens, so read the fixed ADTS
 * header directly instead. This is container parsing only: no compressed
 * audio frame is decoded and the original AVIO position is restored.
 */
static void metadata_complete_adts_parameters(
    AVFormatContext *format,
    AVCodecParameters *parameters
) {
    static const int sample_rates[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    };
    uint8_t bytes[64 * 1024];
    int64_t original_position;
    int byte_count;
    int index;

    if (format == NULL || format->pb == NULL || parameters == NULL ||
        parameters->codec_id != AV_CODEC_ID_AAC ||
        (parameters->sample_rate > 0 && parameters->ch_layout.nb_channels > 0)) {
        return;
    }
    original_position = avio_tell(format->pb);
    if (avio_seek(format->pb, 0, SEEK_SET) < 0) return;
    byte_count = avio_read(format->pb, bytes, sizeof(bytes));
    if (original_position >= 0) avio_seek(format->pb, original_position, SEEK_SET);
    if (byte_count < 7) return;

    for (index = 0; index <= byte_count - 7; index++) {
        int sample_rate_index;
        int channel_configuration;
        if (bytes[index] != 0xff || (bytes[index + 1] & 0xf6) != 0xf0) continue;
        sample_rate_index = (bytes[index + 2] >> 2) & 0x0f;
        channel_configuration = ((bytes[index + 2] & 0x01) << 2) |
            ((bytes[index + 3] >> 6) & 0x03);
        if (sample_rate_index >= (int) (sizeof(sample_rates) / sizeof(sample_rates[0])) ||
            channel_configuration <= 0 || channel_configuration > 7) {
            continue;
        }
        if (parameters->sample_rate <= 0) {
            parameters->sample_rate = sample_rates[sample_rate_index];
        }
        if (parameters->ch_layout.nb_channels <= 0) {
            av_channel_layout_uninit(&parameters->ch_layout);
            av_channel_layout_default(&parameters->ch_layout, channel_configuration);
        }
        return;
    }
}

static int metadata_set_error(y2_metadata_probe *probe, int result, const char *operation) {
    char ffmpeg_error[AV_ERROR_MAX_STRING_SIZE];
    probe->error_category = metadata_error_category(result);
    av_make_error_string(ffmpeg_error, sizeof(ffmpeg_error), result);
    snprintf(
        probe->error_detail,
        sizeof(probe->error_detail),
        "%s: %s (%d)",
        operation,
        ffmpeg_error,
        result
    );
    probe->error_detail[sizeof(probe->error_detail) - 1] = '\0';
    return -1;
}

/**
 * Opens only libavformat from this JNI layer. avformat_find_stream_info reads a
 * bounded amount of container data to finish stream parameters and metadata;
 * the impossible codec allowlist below prevents its internal probe contexts
 * from opening a decoder or decoding frames.
 */
static int metadata_probe_open(
    y2_metadata_probe *probe,
    const char *path,
    int maximum_artwork_bytes
) {
    int64_t phase_started_us;
    int stream_index;
    unsigned int index;
    int result;

    memset(probe, 0, sizeof(*probe));
    phase_started_us = av_gettime_relative();
    y2_crash_stage = Y2_CRASH_STAGE_ALLOCATE_FORMAT;
    probe->format = avformat_alloc_context();
    if (probe->format == NULL) {
        probe->setup_us = av_gettime_relative() - phase_started_us;
        probe->timed_phases |= Y2_METADATA_TIMED_SETUP;
        return metadata_set_error(probe, AVERROR(ENOMEM), "allocate metadata probe");
    }
    y2_crash_stage = Y2_CRASH_STAGE_CONFIGURE_FORMAT;
    probe->format->probesize = atomic_load_explicit(
        &metadata_probe_bytes, memory_order_relaxed
    );
    probe->format->max_analyze_duration = atomic_load_explicit(
        &metadata_analyze_us, memory_order_relaxed
    );
    probe->format->max_streams = Y2_METADATA_MAX_STREAMS;
    probe->format->flags |= AVFMT_FLAG_NOBUFFER;
    probe->format->skip_estimate_duration_from_pts = 1;
    probe->format->skip_attached_picture_payloads = maximum_artwork_bytes <= 0;
    probe->format->max_attached_picture_payload_size = maximum_artwork_bytes;
    probe->deadline_us = av_gettime_relative() + Y2_METADATA_DEADLINE_US;
    probe->format->interrupt_callback.callback = metadata_interrupt_callback;
    probe->format->interrupt_callback.opaque = probe;
    /*
     * avformat_find_stream_info may otherwise open a decoder internally when
     * container/parser parameters are incomplete. An impossible allowlist
     * preserves bounded packet/header probing while guaranteeing scan-time
     * audio is never decoded.
     */
    probe->format->codec_whitelist = av_strdup("__y2_metadata_no_decoder__");
    if (probe->format->codec_whitelist == NULL) {
        probe->setup_us = av_gettime_relative() - phase_started_us;
        probe->timed_phases |= Y2_METADATA_TIMED_SETUP;
        return metadata_set_error(probe, AVERROR(ENOMEM), "configure metadata probe");
    }
    probe->format->protocol_whitelist = av_strdup("file");
    probe->format->format_whitelist = av_strdup("aac,aiff,asf,flac,mov,mp3,ogg,wav");
    if (probe->format->protocol_whitelist == NULL ||
        probe->format->format_whitelist == NULL) {
        probe->setup_us = av_gettime_relative() - phase_started_us;
        probe->timed_phases |= Y2_METADATA_TIMED_SETUP;
        return metadata_set_error(probe, AVERROR(ENOMEM), "restrict metadata input");
    }
    probe->setup_us = av_gettime_relative() - phase_started_us;
    probe->timed_phases |= Y2_METADATA_TIMED_SETUP;

    y2_crash_stage = Y2_CRASH_STAGE_OPEN_INPUT;
    phase_started_us = av_gettime_relative();
    result = avformat_open_input(&probe->format, path, NULL, NULL);
    probe->open_us = av_gettime_relative() - phase_started_us;
    probe->timed_phases |= Y2_METADATA_TIMED_OPEN;
    if (result < 0) {
        return metadata_set_error(probe, result, "open metadata source");
    }
    y2_crash_stage = Y2_CRASH_STAGE_FIND_STREAM_INFO;
    phase_started_us = av_gettime_relative();
    metadata_decoder_rejection_expected = 1;
    result = avformat_find_stream_info(probe->format, NULL);
    probe->stream_info_us = av_gettime_relative() - phase_started_us;
    probe->timed_phases |= Y2_METADATA_TIMED_STREAM_INFO;
    metadata_decoder_rejection_expected = 0;
    if (result < 0) {
        return metadata_set_error(probe, result, "read metadata stream information");
    }
    /*
     * Do not use av_find_best_stream here. For audio it rejects candidates
     * whose sample rate/channel layout remain unknown, which is exactly the
     * expected state of raw ADTS after the no-decoder stream-info pass. The
     * demuxer has already identified codec_type; select its first/default audio
     * stream without consulting avcodec.
     */
    y2_crash_stage = Y2_CRASH_STAGE_FIND_AUDIO_STREAM;
    phase_started_us = av_gettime_relative();
    stream_index = -1;
    for (index = 0; index < probe->format->nb_streams; index++) {
        AVStream *candidate = probe->format->streams[index];
        if (candidate->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) continue;
        if (stream_index < 0 ||
            (candidate->disposition & AV_DISPOSITION_DEFAULT) != 0) {
            stream_index = (int) index;
        }
        if ((candidate->disposition & AV_DISPOSITION_DEFAULT) != 0) break;
    }
    probe->select_stream_us = av_gettime_relative() - phase_started_us;
    probe->timed_phases |= Y2_METADATA_TIMED_SELECT_STREAM;
    if (stream_index < 0) {
        return metadata_set_error(probe, stream_index, "select metadata audio stream");
    }
    probe->stream = probe->format->streams[stream_index];
    metadata_complete_adts_parameters(probe->format, probe->stream->codecpar);
    return 0;
}

static void metadata_probe_close(y2_metadata_probe *probe) {
    avformat_close_input(&probe->format);
    probe->stream = NULL;
}

static const char *metadata_find_in_dictionary(AVDictionary *dictionary, const char *const *keys) {
    int index;
    for (index = 0; keys[index] != NULL; index++) {
        const AVDictionaryEntry *entry = av_dict_get(dictionary, keys[index], NULL, 0);
        if (entry != NULL && entry->value != NULL && entry->value[0] != '\0') {
            return entry->value;
        }
    }
    return NULL;
}

static const char *metadata_find(
    const y2_metadata_probe *probe,
    const char *const *keys
) {
    const char *value = metadata_find_in_dictionary(probe->stream->metadata, keys);
    return value != NULL ? value : metadata_find_in_dictionary(probe->format->metadata, keys);
}

static int metadata_positive_number(const char *value, int allow_fraction) {
    char *end;
    long parsed;
    if (value == NULL) {
        return 0;
    }
    errno = 0;
    while (*value == ' ' || *value == '\t') {
        value++;
    }
    parsed = strtol(value, &end, 10);
    if (errno != 0 || end == value || parsed <= 0 || parsed > INT_MAX) {
        return 0;
    }
    while (*end == ' ' || *end == '\t') {
        end++;
    }
    if (allow_fraction && *end == '/') {
        char *total_end;
        long total;
        end++;
        while (*end == ' ' || *end == '\t') {
            end++;
        }
        errno = 0;
        total = strtol(end, &total_end, 10);
        if (errno != 0 || total_end == end || total <= 0) {
            return 0;
        }
        end = total_end;
        while (*end == ' ' || *end == '\t') {
            end++;
        }
    }
    if (*end != '\0') {
        return 0;
    }
    return (int) parsed;
}

static int metadata_fraction_total(const char *value) {
    const char *separator;
    if (metadata_positive_number(value, 1) <= 0) return 0;
    separator = strchr(value, '/');
    return separator == NULL ? 0 : metadata_positive_number(separator + 1, 0);
}

static int metadata_year(const char *date, const char *year) {
    int month;
    int day;
    char separator;
    int parsed = metadata_positive_number(year, 0);
    if (parsed > 0) {
        return parsed >= 1000 && parsed <= 9999 ? parsed : 0;
    }
    if (date == NULL || date[0] < '0' || date[0] > '9' ||
        date[1] < '0' || date[1] > '9' ||
        date[2] < '0' || date[2] > '9' ||
        date[3] < '0' || date[3] > '9') {
        return 0;
    }
    parsed = (date[0] - '0') * 1000 + (date[1] - '0') * 100 +
        (date[2] - '0') * 10 + (date[3] - '0');
    if (parsed < 1000 || date[4] == '\0') {
        return parsed >= 1000 ? parsed : 0;
    }
    separator = date[4];
    if ((separator != '-' && separator != '/') ||
        date[5] < '0' || date[5] > '9' ||
        date[6] < '0' || date[6] > '9') {
        return 0;
    }
    month = (date[5] - '0') * 10 + (date[6] - '0');
    if (month < 1 || month > 12) {
        return 0;
    }
    if (date[7] == '\0') {
        return parsed;
    }
    if (date[7] != separator ||
        date[8] < '0' || date[8] > '9' ||
        date[9] < '0' || date[9] > '9') {
        return 0;
    }
    day = (date[8] - '0') * 10 + (date[9] - '0');
    if (day < 1 || day > 31 ||
        (date[10] != '\0' && date[10] != 'T' && date[10] != ' ')) {
        return 0;
    }
    return parsed;
}

static int64_t metadata_duration_ms(const y2_metadata_probe *probe) {
    if (probe->stream->duration != AV_NOPTS_VALUE) {
        return av_rescale_q(
            probe->stream->duration,
            probe->stream->time_base,
            (AVRational) { 1, 1000 }
        );
    }
    if (probe->format->duration > 0) {
        return probe->format->duration / (AV_TIME_BASE / 1000);
    }
    return 0;
}

static int64_t metadata_bytes_read(const y2_metadata_probe *probe) {
    return probe->format != NULL && probe->format->pb != NULL &&
        probe->format->pb->bytes_read > 0
        ? probe->format->pb->bytes_read
        : 0;
}

static int metadata_bit_depth(const AVCodecParameters *parameters) {
    if (parameters == NULL) return 0;
    /* Lossy transform codecs do not carry a source PCM bit-depth contract. */
    switch (parameters->codec_id) {
        case AV_CODEC_ID_AAC:
        case AV_CODEC_ID_MP3:
        case AV_CODEC_ID_VORBIS:
        case AV_CODEC_ID_OPUS:
            return 0;
        default:
            break;
    }
    if (parameters->bits_per_raw_sample > 0) return parameters->bits_per_raw_sample;
    if (parameters->codec_id == AV_CODEC_ID_FLAC &&
        parameters->extradata != NULL && parameters->extradata_size >= 14) {
        /* STREAMINFO: 20 sample-rate, 3 channel and 5 bits-per-sample bits. */
        return (((parameters->extradata[12] & 0x01) << 4) |
            (parameters->extradata[13] >> 4)) + 1;
    }
    if (parameters->bits_per_coded_sample > 0) return parameters->bits_per_coded_sample;
    return av_get_bits_per_sample(parameters->codec_id);
}

static int metadata_artwork_rank(const AVStream *stream) {
    const AVDictionaryEntry *comment = av_dict_get(stream->metadata, "comment", NULL, 0);
    const AVDictionaryEntry *title = av_dict_get(stream->metadata, "title", NULL, 0);
    if ((comment != NULL && av_stristr(comment->value, "front") != NULL) ||
        (title != NULL && av_stristr(title->value, "front") != NULL)) {
        return 2;
    }
    return 1;
}

static const AVStream *metadata_artwork_stream(
    const AVFormatContext *format,
    int require_payload
) {
    unsigned int index;
    int best_rank = 0;
    const AVStream *best = NULL;
    for (index = 0; index < format->nb_streams; index++) {
        const AVStream *stream = format->streams[index];
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0 &&
            (!require_payload || (stream->attached_pic.data != NULL &&
                stream->attached_pic.size > 0))) {
            int rank = metadata_artwork_rank(stream);
            if (rank > best_rank) {
                best = stream;
                best_rank = rank;
            }
        }
    }
    return best;
}

/* Standard UTF-8 to UTF-16, avoiding NewStringUTF's modified-UTF-8 contract. */
static jstring metadata_java_string(JNIEnv *env, const char *value) {
    const unsigned char *input;
    size_t byte_count;
    size_t input_index = 0;
    size_t output_index = 0;
    jchar *characters;
    jstring result;

    if (value == NULL) {
        return NULL;
    }
    byte_count = strnlen(value, Y2_METADATA_TEXT_BYTES + 1);
    if (byte_count > Y2_METADATA_TEXT_BYTES) {
        byte_count = Y2_METADATA_TEXT_BYTES;
    }
    characters = av_malloc_array(byte_count + 1, sizeof(*characters));
    if (characters == NULL) {
        jclass error_class = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (error_class != NULL) {
            (*env)->ThrowNew(env, error_class, "metadata text allocation failed");
            (*env)->DeleteLocalRef(env, error_class);
        }
        return NULL;
    }
    input = (const unsigned char *) value;
    while (input_index < byte_count) {
        uint32_t codepoint;
        unsigned char first = input[input_index];
        size_t continuation_count;
        size_t offset;
        uint32_t minimum;

        if (first < 0x80) {
            codepoint = first;
            continuation_count = 0;
            minimum = 0;
        } else if ((first & 0xe0) == 0xc0) {
            codepoint = first & 0x1f;
            continuation_count = 1;
            minimum = 0x80;
        } else if ((first & 0xf0) == 0xe0) {
            codepoint = first & 0x0f;
            continuation_count = 2;
            minimum = 0x800;
        } else if ((first & 0xf8) == 0xf0) {
            codepoint = first & 0x07;
            continuation_count = 3;
            minimum = 0x10000;
        } else {
            codepoint = 0xfffd;
            continuation_count = 0;
            minimum = 0;
        }

        if (codepoint != 0xfffd && input_index + continuation_count >= byte_count) {
            codepoint = 0xfffd;
            continuation_count = 0;
        }
        for (offset = 1; codepoint != 0xfffd && offset <= continuation_count; offset++) {
            unsigned char next = input[input_index + offset];
            if ((next & 0xc0) != 0x80) {
                codepoint = 0xfffd;
                continuation_count = 0;
                break;
            }
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if (codepoint < minimum || codepoint > 0x10ffff ||
            (codepoint >= 0xd800 && codepoint <= 0xdfff)) {
            codepoint = 0xfffd;
            continuation_count = 0;
        }
        input_index += continuation_count + 1;
        if (codepoint <= 0xffff) {
            characters[output_index++] = (jchar) codepoint;
        } else {
            codepoint -= 0x10000;
            characters[output_index++] = (jchar) (0xd800 + (codepoint >> 10));
            characters[output_index++] = (jchar) (0xdc00 + (codepoint & 0x3ff));
        }
    }
    result = (*env)->NewString(env, characters, (jsize) output_index);
    av_free(characters);
    return result;
}

static jobject metadata_java_result(
    JNIEnv *env,
    y2_metadata_probe *probe,
    int success
) {
    static const char *const title_keys[] = { "title", NULL };
    static const char *const artist_keys[] = { "artist", "author", NULL };
    static const char *const album_keys[] = { "album", NULL };
    static const char *const album_artist_keys[] = {
        "album_artist", "albumartist", "album artist", NULL
    };
    static const char *const composer_keys[] = { "composer", NULL };
    static const char *const genre_keys[] = { "genre", NULL };
    static const char *const date_keys[] = {
        "date", "original_date", "originaldate", "creation_time", "year", NULL
    };
    static const char *const comment_keys[] = { "comment", "comments", "description", NULL };
    static const char *const year_keys[] = { "year", NULL };
    static const char *const track_keys[] = {
        "track", "tracknumber", "track_number", NULL
    };
    static const char *const disc_keys[] = {
        "disc", "discnumber", "disc_number", "disk", "part_number", NULL
    };
    jstring strings[11] = { NULL };
    jobject result = NULL;
    const AVCodecParameters *parameters = success ? probe->stream->codecpar : NULL;
    y2_replay_gain_values replay_gain = { INT32_MIN, 0, INT32_MIN, 0 };
    const char *title = NULL;
    const char *artist = NULL;
    const char *album = NULL;
    const char *album_artist = NULL;
    const char *composer = NULL;
    const char *genre = NULL;
    int bit_depth = 0;
    int64_t bitrate = 0;
    const char *date = NULL;
    const char *comment = NULL;
    const char *track = NULL;
    const char *disc = NULL;
    const char *year = NULL;
    int track_number = 0;
    int track_total = 0;
    int disc_number = 0;
    int disc_total = 0;
    int parsed_year = 0;
    int has_artwork = 0;
    int64_t phase_started_us;
    int index;

#define SET_METADATA_STRING(slot, value) do { \
    strings[(slot)] = metadata_java_string(env, (value)); \
    if ((*env)->ExceptionCheck(env)) goto cleanup; \
} while (0)

    SET_METADATA_STRING(0, success ? NULL : probe->error_detail);
    if (success) {
        phase_started_us = av_gettime_relative();
        title = metadata_find(probe, title_keys);
        artist = metadata_find(probe, artist_keys);
        album = metadata_find(probe, album_keys);
        album_artist = metadata_find(probe, album_artist_keys);
        composer = metadata_find(probe, composer_keys);
        genre = metadata_find(probe, genre_keys);
        date = metadata_find(probe, date_keys);
        comment = metadata_find(probe, comment_keys);
        track = metadata_find(probe, track_keys);
        disc = metadata_find(probe, disc_keys);
        year = metadata_find(probe, year_keys);
        track_number = metadata_positive_number(track, 1);
        track_total = metadata_fraction_total(track);
        disc_number = metadata_positive_number(disc, 1);
        disc_total = metadata_fraction_total(disc);
        parsed_year = metadata_year(date, year);
        probe->dictionary_us = av_gettime_relative() - phase_started_us;
        probe->timed_phases |= Y2_METADATA_TIMED_DICTIONARY;

        phase_started_us = av_gettime_relative();
        replay_gain = resolved_replay_gain(probe->stream, probe->format);
        probe->replay_gain_us = av_gettime_relative() - phase_started_us;
        probe->timed_phases |= Y2_METADATA_TIMED_REPLAY_GAIN;

        phase_started_us = av_gettime_relative();
        has_artwork = metadata_artwork_stream(probe->format, 0) != NULL;
        probe->artwork_us = av_gettime_relative() - phase_started_us;
        probe->timed_phases |= Y2_METADATA_TIMED_ARTWORK;

        SET_METADATA_STRING(1, title);
        SET_METADATA_STRING(2, artist);
        SET_METADATA_STRING(3, album);
        SET_METADATA_STRING(4, album_artist);
        SET_METADATA_STRING(5, composer);
        SET_METADATA_STRING(6, genre);
        SET_METADATA_STRING(7, date);
        SET_METADATA_STRING(8, comment);
        SET_METADATA_STRING(9, avcodec_get_name(parameters->codec_id));
        SET_METADATA_STRING(10, probe->format->iformat->name);
        bit_depth = metadata_bit_depth(parameters);
        bitrate = parameters->bit_rate > 0 ? parameters->bit_rate : probe->format->bit_rate;
    }

    result = (*env)->NewObject(
        env,
        metadata_class,
        metadata_constructor,
        (jboolean) success,
        (jint) probe->error_category,
        strings[0], strings[1], strings[2], strings[3], strings[4],
        strings[5], strings[6], strings[7], strings[8],
        (jint) track_number,
        (jint) track_total,
        (jint) disc_number,
        (jint) disc_total,
        (jint) parsed_year,
        (jlong) (success ? metadata_duration_ms(probe) : 0),
        strings[9],
        strings[10],
        (jlong) (success ? bitrate : 0),
        (jint) (success ? parameters->sample_rate : 0),
        (jint) (success ? bit_depth : 0),
        (jint) (success ? parameters->ch_layout.nb_channels : 0),
        (jint) replay_gain.track_gain,
        (jlong) replay_gain.track_peak,
        (jint) replay_gain.album_gain,
        (jlong) replay_gain.album_peak,
        (jlong) metadata_bytes_read(probe),
        (jboolean) has_artwork
    );
cleanup:
#undef SET_METADATA_STRING
    for (index = 0; index < 11; index++) {
        if (strings[index] != NULL) {
            (*env)->DeleteLocalRef(env, strings[index]);
        }
    }
    return result;
}

static void native_reset_metadata_profile(JNIEnv *env, jobject instance) {
    (void) env;
    (void) instance;
    pthread_mutex_lock(&metadata_profile_mutex);
    memset(&metadata_profile, 0, sizeof(metadata_profile));
    pthread_mutex_unlock(&metadata_profile_mutex);
}

static jboolean native_configure_metadata_probe_limits(
    JNIEnv *env,
    jobject instance,
    jint probe_bytes,
    jint analyze_us
) {
    (void) env;
    (void) instance;
    if (probe_bytes < 32 * 1024 || probe_bytes > 5 * 1024 * 1024 ||
        analyze_us < 0 || analyze_us > 10 * AV_TIME_BASE) {
        return JNI_FALSE;
    }
    atomic_store_explicit(&metadata_probe_bytes, probe_bytes, memory_order_relaxed);
    atomic_store_explicit(&metadata_analyze_us, analyze_us, memory_order_relaxed);
    return JNI_TRUE;
}

static jlongArray native_metadata_profile(JNIEnv *env, jobject instance) {
    jlong values[37];
    jlongArray result;
    int phase_index;
    (void) instance;

    pthread_mutex_lock(&metadata_profile_mutex);
    values[0] = metadata_profile.calls;
    values[1] = metadata_profile.successes;
    values[2] = metadata_profile.failures;
    values[3] = metadata_profile.total_us;
    values[4] = metadata_profile.maximum_us;
    values[5] = metadata_profile.bytes_read;
    values[6] = metadata_profile.maximum_bytes_read;
    for (phase_index = 0; phase_index < 10; phase_index++) {
        int base = 7 + phase_index * 3;
        values[base] = metadata_profile.phase[phase_index].count;
        values[base + 1] = metadata_profile.phase[phase_index].total_us;
        values[base + 2] = metadata_profile.phase[phase_index].maximum_us;
    }
    pthread_mutex_unlock(&metadata_profile_mutex);

    result = (*env)->NewLongArray(env, 37);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 37, values);
    }
    return result;
}

static jobject native_read_metadata(JNIEnv *env, jobject instance, jstring path) {
    const char *native_path;
    y2_metadata_probe probe;
    int64_t total_started_us;
    int64_t phase_started_us;
    int64_t path_us;
    int64_t java_result_us;
    int64_t close_us;
    int64_t bytes_read;
    int success;
    jobject result;
    (void) instance;

    total_started_us = av_gettime_relative();
    y2_crash_begin(Y2_CRASH_OPERATION_METADATA);
    if (path == NULL) {
        y2_crash_end();
        return NULL;
    }
    phase_started_us = av_gettime_relative();
    native_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (native_path == NULL) {
        y2_crash_end();
        return NULL;
    }
    y2_crash_set_path(native_path);
    path_us = av_gettime_relative() - phase_started_us;
    success = metadata_probe_open(&probe, native_path, 0) == 0;
    (*env)->ReleaseStringUTFChars(env, path, native_path);
    y2_crash_stage = Y2_CRASH_STAGE_BUILD_JAVA_RESULT;
    phase_started_us = av_gettime_relative();
    result = metadata_java_result(env, &probe, success);
    java_result_us = av_gettime_relative() - phase_started_us;
    java_result_us -= probe.dictionary_us + probe.replay_gain_us + probe.artwork_us;
    if (java_result_us < 0) java_result_us = 0;
    bytes_read = metadata_bytes_read(&probe);
    y2_crash_stage = Y2_CRASH_STAGE_CLOSE_PROBE;
    phase_started_us = av_gettime_relative();
    metadata_probe_close(&probe);
    close_us = av_gettime_relative() - phase_started_us;
    metadata_profile_record(
        &probe,
        success,
        av_gettime_relative() - total_started_us,
        path_us,
        java_result_us,
        close_us,
        bytes_read
    );
    y2_crash_end();
    return result;
}

static jbyteArray native_read_artwork(
    JNIEnv *env,
    jobject instance,
    jstring path,
    jint maximum_bytes
) {
    const char *native_path;
    const AVStream *artwork;
    y2_metadata_probe probe;
    jbyteArray result = NULL;
    (void) instance;

    y2_crash_begin(Y2_CRASH_OPERATION_ARTWORK);
    if (maximum_bytes <= 0) {
        y2_crash_end();
        return NULL;
    }
    if (path == NULL) {
        y2_crash_end();
        return NULL;
    }
    native_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (native_path == NULL) {
        y2_crash_end();
        return NULL;
    }
    y2_crash_set_path(native_path);
    if (metadata_probe_open(&probe, native_path, maximum_bytes) == 0) {
        y2_crash_stage = Y2_CRASH_STAGE_READ_ARTWORK;
        artwork = metadata_artwork_stream(probe.format, 1);
        if (artwork != NULL && artwork->attached_pic.size <= maximum_bytes) {
            result = (*env)->NewByteArray(env, artwork->attached_pic.size);
            if (result != NULL) {
                (*env)->SetByteArrayRegion(
                    env,
                    result,
                    0,
                    artwork->attached_pic.size,
                    (const jbyte *) artwork->attached_pic.data
                );
            }
        }
    }
    y2_crash_stage = Y2_CRASH_STAGE_CLOSE_PROBE;
    metadata_probe_close(&probe);
    (*env)->ReleaseStringUTFChars(env, path, native_path);
    y2_crash_end();
    return result;
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
        "FFmpeg %s; avformat=%u; avcodec=%u; avutil=%u; swresample=%u; internal-pcm=float32; android-api=19; abi=armeabi-v7a",
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
    if (metadata_decoder_rejection_expected &&
        strcmp(format, "Codec (%s) not on whitelist '%s'\n") == 0) {
        return;
    }
    priority = level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    __android_log_vprint(priority, "y2audio", format, args);
}

static const JNINativeMethod native_audio_methods[] = {
    { "nativeConfigureCrashReporter", "(Ljava/lang/String;)Z", (void *) native_configure_crash_reporter },
    { "nativeCreate", "()J", (void *) native_create },
    { "nativeOpen", "(JLjava/lang/String;II)I", (void *) native_open },
    { "nativeDecode", "(JLjava/nio/ByteBuffer;I)I", (void *) native_decode },
    { "nativeSeek", "(JJ)I", (void *) native_seek },
    { "nativeRequestAbort", "(J)V", (void *) native_request_abort },
    { "nativeDurationMs", "(J)J", (void *) native_duration_ms },
    { "nativeSourceSampleRate", "(J)I", (void *) native_source_sample_rate },
    { "nativeSourceChannels", "(J)I", (void *) native_source_channels },
    { "nativeDecodeWarningCount", "(J)I", (void *) native_decode_warning_count },
    { "nativeCodecName", "(J)Ljava/lang/String;", (void *) native_codec_name },
    { "nativeReplayGainTrackGain", "(J)I", (void *) native_replay_gain_track_gain },
    { "nativeReplayGainTrackPeak", "(J)J", (void *) native_replay_gain_track_peak },
    { "nativeReplayGainAlbumGain", "(J)I", (void *) native_replay_gain_album_gain },
    { "nativeReplayGainAlbumPeak", "(J)J", (void *) native_replay_gain_album_peak },
    { "nativeErrorCategory", "(J)I", (void *) native_error_category },
    { "nativeErrorDetail", "(J)Ljava/lang/String;", (void *) native_error_detail },
    { "nativeClose", "(J)V", (void *) native_close },
    { "nativeBuildInformation", "()Ljava/lang/String;", (void *) native_build_information },
    { "nativeConfigureMetadataProbeLimits", "(II)Z", (void *) native_configure_metadata_probe_limits },
    { "nativeResetMetadataProfile", "()V", (void *) native_reset_metadata_profile },
    { "nativeMetadataProfile", "()[J", (void *) native_metadata_profile },
    { "nativeReadMetadata", "(Ljava/lang/String;)Lcom/schulzcode/y2player/library/FfmpegMetadata;", (void *) native_read_metadata },
    { "nativeReadArtwork", "(Ljava/lang/String;I)[B", (void *) native_read_artwork }
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jclass native_audio_class;
    jclass local_metadata_class;
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
    local_metadata_class = (*env)->FindClass(
        env,
        "com/schulzcode/y2player/library/FfmpegMetadata"
    );
    if (local_metadata_class == NULL) {
        return JNI_ERR;
    }
    metadata_class = (*env)->NewGlobalRef(env, local_metadata_class);
    (*env)->DeleteLocalRef(env, local_metadata_class);
    if (metadata_class == NULL) {
        return JNI_ERR;
    }
    metadata_constructor = (*env)->GetMethodID(
        env,
        metadata_class,
        "<init>",
        "(ZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIIJLjava/lang/String;Ljava/lang/String;JIIIIJIJJZ)V"
    );
    if (metadata_constructor == NULL) {
        (*env)->DeleteGlobalRef(env, metadata_class);
        metadata_class = NULL;
        return JNI_ERR;
    }

    av_log_set_level(AV_LOG_ERROR);
    av_log_set_callback(y2_log_callback);
    return JNI_VERSION_1_6;
}
