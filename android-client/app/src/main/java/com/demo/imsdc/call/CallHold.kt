package com.demo.imsdc.call

/**
 * The call is on hold (this side or the other). Per 3GPP TS 24.186 clause 10.20 every media line and every
 * data channel line is then a=inactive, so nothing is exchanged: the call's sound and picture (RtpMediaManager),
 * the mini-app messages on the data channels, photo transfers, new app downloads and the shared video or screen.
 */
object CallHold {
    @Volatile var active = false
}
