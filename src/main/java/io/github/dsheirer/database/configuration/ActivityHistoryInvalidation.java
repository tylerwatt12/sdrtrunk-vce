/* Copyright (C) 2026 Dennis Sheirer. GNU General Public License, version 3 or later. */
package io.github.dsheirer.database.configuration;

import java.nio.file.Path;

/** A committed configuration transaction removed Activity through a foreign-key cascade. */
public record ActivityHistoryInvalidation(Path databasePath) { }
