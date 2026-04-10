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
extreme abuse. Practically everyone else is on a UHF P25 Phase 2 system, with the control channel on Phase 1 FDMA, and
the working channels on Phase 2 TDMA. You've got to bring some decent equipment to decode the Phase 2 stuff, as PPM
drift from the oscillators is going to result in CRC errors and bad decodes. I struggled with getting my usual junk
drawer of RTL-SDR v4 dongles to work reliably with it, and ultimately decided to go with better radios, settling on
SDRPlay nRSP-ST models.

As with any of the RSP models, these network RSPs are communicated with via
[SDRconnect](https://www.sdrplay.com/sdrconnect/).

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
- We default the covered bandwidth to 5MHz as a reasonable default for P25; 2MHz will almost always be too small, and 5MHz will typically be enough; large simulcast systems may require more, but it's a reasonable starting point. If instead, you're just using this for public service NBFM, 500kHz is probably going to be plenty; agencies will tend to use channels clustered in a small span.
- Note that 5MHz bandwidth, Full IQ is going to require almost exactly 20MBps (note, that's megabytes, not megabits) of network bandwidth per RSP in play, so be cognizant of that in terms of your network setup; oodles of RSPs is going to require oodles of bandwidth. Ideally, keep everything on the same switch and don't cross a router, and I'd avoid using WiFi.
- All of this is pretty much just "get it working reliably for me, in my particular scenario". You might find it interesting or useful, but bottom line, this is just a line of experimentation for me, not something that I'd expect to do a PR for any time soon. If that's something you'd like to do, have at it; proper attribution to W2NJL's work and my meager efforts here would be apropos in that case.
- My present focus is on reliability; introducing dependency on a separate process creates some complications in terms of ensuring that the processes auto-recover from transient errors, crashes, etc., which isn't the case when talking directly to a dongle. The interface to the radios is fairly thin at the moment; I've only worked in rate, antenna selection, and LNA gain so far. However, they are outstanding radios, and I haven't needed to do any tweaking yet, so it hasn't been a priority, and I'm not sure it will be -- heck, the things go down to 1KHz; if you can literally discern audio, how much tweaking do you need, really.
- The NBFM path in this fork now has a post-demod audio shaping chain. Available stages include de-emphasis, high-pass filtering, low-pass filtering, voice enhancement, bass boost, and output gain.
- The NBFM high-pass stage now runs inside the decoder's own post-processing chain rather than downstream in the generic audio module. The current order is: de-emphasis, resample, high-pass, low-pass, voice enhance, bass boost, then output gain.
- With NBFM, you might not need any RF gain at all; start with zero and see how it plays; repeaters are typically on the
top of mountains, and they're usually not hard to hear. Rather than more RF gain, the NBFM audio tuning settings are probably
going to be more effective.
- With P25, likewise, with nRSP-ST radios you may not need any RF gain. Follow the instructions in the Wiki and bring it
up if it's increasing signal, but stop when it doing so starts increasing noise. Excessive RF gain is definitely not going
to help, and will cause decode failures.
- For multi-frequency tuner sources, this fork now supports an optional frequency envelope using `min_frequency` and
`max_frequency`. This was added for trunked channels, where we'd like to make more optimal channel center choices. The
problem this addresses is that center frequency selection was not good, resulting in, in my case, the control channels
being in the marginal upper portion of the passband, and about 1MHz of the unusuable lower portion of the passband being
in play. In practice, that meant a 5MHz sample rate could look insufficient, and losing lock was not unusual, when the real
issue was poor center-frequency selection.
- When a frequency envelope is present, sdrtrunk uses that full span to pre-position idle polyphase tuners and to guide
subsequent control-channel reacquisition, so the tuner stays centered in the passband for the whole site.
- The Frequency Editor exposes these values as `Minimum (MHz)` and `Maximum (MHz)`, and the RadioReference site import
path populates them automatically from the full imported site frequency list.
- This does not change the actual rotating control-channel list. The `Control (MHz)` entries are still the frequencies
used for control-channel acquisition and rotation; the envelope is only a tuner-centering hint.
- While the SDRconnect tuner type will display drift and PPM, the auto-correct feature is disabled, as the drift will in
general be so low as to be uninteresting.
- Gettting this to work reliably on my system was a bit of a struggle; at this point my conclusion is that OSX Tahoe seems
to get along with JDK 26 a lot better than it got along with 25, at least on my system. I was encountering a lot of 'out of
application memory' issues on a 64GB machine, so my thesis was that perhaps there was some application issue that was the
root cause. This led to an absolutely epic lint run, which did correct a bunch of concerns, but also smoked out a few bugs, which I'll start feeding to the maintainer when he picks this up again. This now builds completely cleanly with zero
warnings against JDK 26, and it's got a clean bill of health from Sonar, with the exception of the usual complexity metrics
that Sonar gets peeved about in anything more complicated than a Hello World.
- I've got a parallel version of the jmbe library that this uses likewise lint-clean and optimized that can be used instead
of the public version; if you auto-install the library in the configuration panels, you'll get the public version, not mine. My version has a number of fixes that arose from problems encountered in testing; if you A/B them and mine does a better job for you, I'd like to know.
- One key thread I'm presently pulling on is that the default P25 channel frequency rotation delay of 400ms might be a bit too aggressive; it seems fine for the most part, but in debug tracing I see it missing from time to time by ~200ms or so, and it seems as if it's being just a bit too quick to pull the trigger into rotation every so often.
- I've limited the JVM maximum heap to 2GB in `build.gradle`, as my machine has 64GB, and thus apparently the JVM defaults
decide that it's a 'server', and does a 25% of memory virtual allocation of 16GB, which is a bit on the generous side for
the testing I'm doing at present; 2GB is more than enough. If you need more, just increase the settings in `build.gradle`.

## Developer Build and Packaging Notes
This fork is built and smoke-tested with the Gradle wrapper and a Java 26 toolchain, currently using BellSoft Liberica Full JDK for JavaFX and packaging support. Things are set up in such a way as to be ready for Gradle 10, to the point that gradle caching can be enabled, which signficiantly speeds up the build process. I strongly recommend enabling the cache; it's night and day for build performance.

Common local checks:

```
./gradlew compileJava --warning-mode all
./gradlew runtimeZipCurrent --warning-mode all
```

The current runtime zip path produces a conventional zip distribution:

```
build/image/sdr-trunk.zip
```

The macOS app image path uses JPMS:

```
./gradlew jpmsRun --warning-mode all
./gradlew image --warning-mode all
```

The JPMS path builds an explicit `sdr.trunk` module from `src/jpms/java/module-info.java`, packages dependencies into a curated `build/jpms-mods` module directory, and produces:

```
build/image/sdrtrunk.app
```

That path currently uses an `open module` descriptor so Jackson/XML configuration binding and other reflective code paths continue to work while modular packaging is validated. Two dependency bridges are generated during the JPMS build: a merged `usb4java` jar so Apple Silicon native resources are visible from the `usb4java` module, and a renamed `lame.jar` so the `java-lame` dependency has a valid automatic module name.

The original maintainer's README starts here.

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
