![Gradle Build](https://github.com/dsheirer/sdrtrunk/actions/workflows/gradle.yml/badge.svg)
![Nightly Release](https://github.com/dsheirer/sdrtrunk/actions/workflows/nightly.yml/badge.svg)

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

## Fork Status
This repository is a personal experimental fork by W6BAZ and is not intended for general public consumption or support. It is being used to prototype SDRconnect integration, macOS packaging behavior, and related workflow changes outside the upstream project.

Notable differences from upstream in this fork:
- SDRconnect support is being exercised against the SDRplay SDRconnect WebSocket API as implemented in SDRconnect 1.0.8.
- Current testing in this fork is focused on nRSP-ST devices. In principle the SDRconnect path should work with other RSP devices exposed through SDRconnect, but that is not the current validation target.
- The current SDRconnect workflow expects device display names such as `nRSP-ST 1` and `nRSP-ST 2`. Serial-number-based device selection is not implemented in this fork yet, and frankly might never be, as I find names much easier to remember.
- Local `SDRconnect_headless` lifecycle management has been added as a convenience feature for local use. If enabled, sdrtrunk can start and stop local headless instances for configured ports. If disabled, sdrtrunk can still connect to SDRconnect instances that were started manually.
- macOS application packaging has only had a minimal work-in-progress pass in this fork. It is good enough for local testing, but should not be treated as a polished or fully supported distribution path.

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

Current assumptions and behavior for this fork:
- SDRconnect tuners are configured per `host:port`, with device name used as selection metadata rather than as part of tuner identity.
- For local loopback hosts, headless startup readiness is checked over the WebSocket API before tuner startup proceeds.
- The convenience headless manager is optional. Users who already run SDRconnect or `SDRconnect_headless` themselves can leave auto-start disabled and sdrtrunk will simply attach to the configured endpoints if they are available.
- All of this is pretty much just "get it working reliably for me, in my particular scenario". You might find it interesting or useful,
but bottom line, this is just a line of experimentation for me, not something that I'd expect to do a PR for any time soon. If that's
something you'd like to do, have at it; proper attribution to W2NJL's work and my meager efforts here would be apropos in that case.

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
