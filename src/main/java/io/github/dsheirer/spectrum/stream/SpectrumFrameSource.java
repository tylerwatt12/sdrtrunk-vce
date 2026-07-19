/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import java.util.function.Consumer;

/**
 * Lifecycle boundary between spectrum production and transport fan-out.
 *
 * <p>Implementations must return promptly from lifecycle methods and must not produce frames inline from
 * {@link #start(Consumer)}.  A source is restartable until it is closed.</p>
 */
public interface SpectrumFrameSource extends AutoCloseable
{
    void start(Consumer<SpectrumFrame> frameConsumer);

    void stop();

    boolean isRunning();

    @Override
    void close();
}
