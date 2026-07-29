/*
 * Audit source for the Y2 stock primary-audio HAL patch.
 *
 * This routine is injected at ELF virtual address 0x000a73e4 by
 * patch_primary_audio_hal.py.  The firmware build does not assemble this file;
 * the patcher carries the independently assembled bytes and verifies every
 * stock address before applying them.  Keep both representations identical.
 *
 * The kernel ABI was recovered from the stock cs43131_ioctl implementation:
 *     ioctl(fd, 0x40044305, sample_rate)
 * The third argument is the numeric rate, not a pointer.
 *
 * Only the two rates whose end-to-end use is currently supportable are sent to
 * the DAC driver.  Higher rates must remain rejected until the MediaTek AFE/I2S
 * path and kernel DAC clock programming are extended together.
 */

    .syntax unified
    .arch armv7-a
    .arm
    .section .text.y2_dac_frequency,"ax",%progbits
    .balign 4

    .global y2_ext_dac_set_playback_freq
    .type y2_ext_dac_set_playback_freq, %function

    .equ y2_frequency_cmd,  0x40044305

    /* Supplied as absolute --defsym values when this audit source is linked. */
    .extern y2_open_plt
    .extern y2_ioctl_plt
    .extern y2_close_plt
    .extern y2_cs43131_path

y2_ext_dac_set_playback_freq:
    push    {r4, r5, r11, lr}
    mov     r4, r0

    movw    r3, #44100
    cmp     r4, r3
    beq     .Lset_frequency
    movw    r3, #48000
    cmp     r4, r3
    bne     .Ldone

.Lset_frequency:
    ldr     r0, .Lpath_delta
.Ladd_path:
    add     r0, pc, r0
    mov     r1, #2                  /* O_RDWR */
    bl      y2_open_plt
    cmp     r0, #0
    blt     .Ldone

    mov     r5, r0
    movw    r1, #:lower16:y2_frequency_cmd
    movt    r1, #:upper16:y2_frequency_cmd
    mov     r2, r4
    bl      y2_ioctl_plt

    mov     r0, r5
    bl      y2_close_plt

.Ldone:
    pop     {r4, r5, r11, pc}

    .balign 4
.Lpath_delta:
    .word   y2_cs43131_path - (.Ladd_path + 8)

    .size y2_ext_dac_set_playback_freq, . - y2_ext_dac_set_playback_freq
