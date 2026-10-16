/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.call;

/** Exact producer binding on one processing-chain-local bus; never a global receiver observation. */
public record AudioActivityCallEvent(String token, int timeslot) {}
