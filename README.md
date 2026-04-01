![Gradle Build](https://github.com/dsheirer/sdrtrunk/actions/workflows/gradle.yml/badge.svg)
![Nightly Release](https://github.com/dsheirer/sdrtrunk/actions/workflows/nightly.yml/badge.svg)

## Fork Status
This repository is a personal experimental fork by W6BAZ and is not intended for general public consumption or support. It is being used to prototype SDRconnect integration, macOS packaging behavior, and related workflow changes outside the upstream project.

## Motivation
I live in California, and as of this writing we're on the cusp of Fire Season, where seemingly half the state burns down.
As a result of this, I become very interested in public service traffic, most notably, the fire services, as wildfire here
doesn't give a lot of warning; as an example, the [Tubbs Fire](https://en.wikipedia.org/wiki/Tubbs_Fire), which is pretty
much the stuff of nightmare.

We have a mix of VHF voice (CalFire) in the 150MHz band, as the wildland fire folks like simple things that work under
extreme abuse; VHF voice is not a big deal to handle. Practically everyone else is on a UHF P25 Phase 2 system, with the
control channel on Phase 1 FDMA, and the working channels on Phase 2 TDMA. You've got to bring some decent equipment
to decode the Phase 2 stuff, as PPM drift from the oscillators is going to result in CRC errors and bad decodes. I
struggled with getting my usual junk drawer of RTL-SDR v4 dongles to work reliably with it, and ultimately decided
to go with better radios, settling on SDRPlay nRSP-ST models.

As with any of the RSP models, these network RSPs are communicated with via `SDRconnect`.

## Experimental Notes
The SDRconnect work in this fork was inspired by, and partially based on, W2NJL's SDRconnect-related work here:

- [W2NJL/sdrtrunk](https://github.com/W2NJL/sdrtrunk)

Current assumptions and behavior:
- SDRconnect support is being exercised against the SDRplay SDRconnect WebSocket API as implemented in SDRconnect 1.0.8.
- Current testing is focused on nRSP-ST devices. In principle the SDRconnect path should work with other RSP devices exposed through SDRconnect, but that is not the current validation target; I don't have any to test with, which presents a bit of a hurdle.
- SDRconnect tuners are configured per `host:port`, with the optional device field used as selection metadata rather than as part of tuner identity.
- Configured SDRconnect endpoints are checked for WebSocket readiness before tuner startup proceeds, regardless of whether the corresponding SDRconnect instance was launched by sdrtrunk, started manually, or is running on another host.
- SDRconnect device selection can use a friendly device name such as `nRSP-ST 1`, a serial number token such as `2405166650`, or a blank value.
- If the device field is left blank, sdrtrunk treats that as automatic selection. For a single tuner, it selects the first advertised SDRconnect device and prefers the `Full IQ` variant when multiple advertised modes are available. If multiple SDRconnect tuners are configured against the same ready endpoint with blank device fields, different advertised devices are assigned to those tuner slots before startup so they do not all attach to the first device in the list.
- Local loopback endpoints can optionally be managed through `SDRconnect_headless`. When auto-start is enabled, sdrtrunk can launch and later stop local headless instances for configured loopback ports that are not already up. When an endpoint is already reachable, sdrtrunk does not try to manage the process and instead checks that endpoint for readiness before tuner startup. If auto-start is disabled and a configured loopback endpoint is not already reachable, sdrtrunk leaves it unavailable rather than launching it. sdrtrunk only stops processes it launched itself — instances that were already running when sdrtrunk started, or that were started manually, are left running when sdrtrunk exits.
- SDRconnect now has its own tuner manager layer, used by the main tuner manager. That is not meant as a general pattern for
every tuner type; it exists because SDRconnect has requirements the other tuner integrations do not, including WebSocket readiness checks, optional external process management, deferred startup, and pre-start device assignment when multiple advertised devices may be present behind one endpoint.
- macOS application packaging has only had a minimal work-in-progress pass. It is good enough for local testing, but should not be treated as a polished or fully supported distribution path.
- We default the covered bandwidth to 5MHz as a reasonable default for P25; 2MHz will almost always be too small, and 5MHz will typically be enough; large simulcast systems may require more, but it's a reasonable starting point. If instead, you're just using this for public service NBFM, 500kHz is probably going to be plenty; agencies will tend to use channels clustered in a small span.
- Note that 5MHz bandwidth, Full IQ is going to require almost exactly 20MBps (note, that's megabytes, not megabits) of network bandwidth per RSP in play, so be cognizant of that in terms of your network setup; oodles of RSPs is going to require oodles of bandwidth. Ideally, keep everything on the same switch and don't cross a router, and I'd avoid using WiFi.
- All of this is pretty much just "get it working reliably for me, in my particular scenario". You might find it interesting or useful, but bottom line, this is just a line of experimentation for me, not something that I'd expect to do a PR for any time soon. If that's something you'd like to do, have at it; proper attribution to W2NJL's work and my meager efforts here would be apropos in that case.
- My present focus is on reliability; introducing dependency on a separate process creates some complications in terms of ensuring that the processes auto-recover from transient errors, crashes, etc., which isn't the case when talking directly to a dongle. The interface to the radios is fairly thin at the moment; I've only worked in rate and antenna selection so far, and  the radios offer a lot more in terms of tuning function. However, they are outstanding radios, and I haven't needed to do any tweaking yet, so it hasn't been a priority, and I'm not sure it will be -- heck, the things go down to 1KHz; if you can literally discern audio, how much tweaking do you need, really.

# MacOS Tahoe 26.1 Users - Attention:
Changes to USB support in Tahoe version 26.x cause sdrtrunk to fail to launch.  Do the following to install the latest libusb and create a symbolic link and then use the nightly build which includes an updated usb4java native library for Tahoe with ARM processor.  There may still be issue(s) with MacOS accessing your USB SDR tuners.

```
brew install libusb --HEAD
cd /opt
sudo mkdir local
cd local
sudo mkdir lib
```
Next, find where brew installed the libusb library, for example: ```/opt/homebrew/Cellar/libusb/HEAD-9ceaa52/lib/libusb-1.0.0.dylib```    Note: the folder "HEAD-9ceaa52" is the version stamp for HEAD when you installed from it.

Finally, create a symbolic link from the installed library to the place where usb4java is expecting to find libusb (/opt/local/lib/libusb-1.0.0.dylib)

```
sudo ln -s /opt/homebrew/Cellar/libusb/HEAD-9ceaa52/lib/libusb-1.0.0.dylib /opt/local/lib/libusb-1.0.0.dylib
```

# sdrtrunk
A cross-platform java application for decoding, monitoring, recording and streaming trunked mobile and related radio protocols using Software Defined Radios (SDR).

* [Help/Wiki Home Page](https://github.com/DSheirer/sdrtrunk/wiki)
* [Getting Started](https://github.com/DSheirer/sdrtrunk/wiki/Getting-Started)
* [User's Manual](https://github.com/DSheirer/sdrtrunk/wiki/User-Manual)
* [Download](https://github.com/DSheirer/sdrtrunk/releases)
* [Support](https://github.com/DSheirer/sdrtrunk/wiki/Support)

![sdrtrunk Application](https://github.com/DSheirer/sdrtrunk/wiki/images/sdrtrunk.png)
**Figure 1:** sdrtrunk Application Screenshot

## Download the Latest Release
All release versions of sdrtrunk are available from the [releases](https://github.com/DSheirer/sdrtrunk/releases) tab.

* **(alpha)** These versions are under development feature previews and likely to contain bugs and unexpected behavior.
* **(beta)** These versions are currently being tested for bugs and functionality prior to final release.
* **(final)** These versions have been tested and are the current release version.

## Download Nightly Software Build
The [nightly](https://github.com/DSheirer/sdrtrunk/releases/tag/nightly) release contains current builds of the software 
for all supported operating systems.  This version of the software may contain bugs and may not run correctly.  However, 
it let's you preview the most recent changes and fixes before the next software release.  **Always backup your 
playlist(s) before you use the nightly builds.**  Note: the nightly release is updated each time code changes are 
committed to the code base, so it's not really 'nightly' as much as it is 'current'.

## Minimum System Requirements
* **Operating System:** Windows (~~32 or~~ 64-bit), Linux (~~32 or~~ 64-bit) or Mac (64-bit, 12.x or higher)
* **CPU:** 4-core
* **RAM:** 8GB or more (preferred).  Depending on usage, 4GB may be sufficient.
