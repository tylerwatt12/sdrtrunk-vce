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

package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.gui.JavaFxWindowManager;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import javafx.application.Application;
import org.junit.jupiter.api.Test;

/**
 * Prevents secondary-window helpers from becoming alternate production launchers that bypass the portable-data lock
 * and exact database bootstrap owned by {@code SDRTrunk.main()}.
 */
class ProductionDatabaseEntrypointBoundaryTest
{
    @Test
    void javaFxWindowManagerRequiresAnAlreadyBootstrappedApplication()
    {
        assertEquals(Object.class, JavaFxWindowManager.class.getSuperclass());
        assertThrows(NoSuchMethodException.class, JavaFxWindowManager.class::getConstructor);
        assertFalse(Arrays.stream(JavaFxWindowManager.class.getDeclaredMethods())
            .anyMatch(ProductionDatabaseEntrypointBoundaryTest::isMainMethod));
        assertFalse(Application.class.isAssignableFrom(JavaFxWindowManager.class));
    }

    private static boolean isMainMethod(Method method)
    {
        return Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()) &&
            method.getName().equals("main") && method.getReturnType() == Void.TYPE &&
            Arrays.equals(method.getParameterTypes(), new Class<?>[] {String[].class});
    }
}
