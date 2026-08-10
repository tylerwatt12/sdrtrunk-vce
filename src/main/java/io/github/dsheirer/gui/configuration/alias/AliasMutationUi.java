/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.AliasAdministrationService;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JavaFX presentation for Alias command failures.
 */
public final class AliasMutationUi
{
    private static final Logger mLog = LoggerFactory.getLogger(AliasMutationUi.class);

    private AliasMutationUi()
    {
    }

    /**
     * Runs an Alias command and presents a concise error when it cannot be applied.
     */
    public static <T> Optional<T> execute(Node owner, String title, Supplier<T> command)
    {
        try
        {
            return Optional.ofNullable(command.get());
        }
        catch(AliasAdministrationService.StaleRevisionException exception)
        {
            show(owner, title, "Aliases changed while this item was open",
                "Reload the Alias and apply your changes again.");
        }
        catch(AliasAdministrationService.PersistenceException exception)
        {
            mLog.error("Unable to persist Alias configuration", exception);
            show(owner, title, "Alias changes were not saved",
                "The previous Alias configuration has been restored.");
        }
        catch(AliasAdministrationService.NotInitializedException |
              AliasAdministrationService.ConfigurationBusyException exception)
        {
            show(owner, title, "Alias configuration is unavailable", exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            show(owner, title, "Alias changes could not be applied", exception.getMessage());
        }

        return Optional.empty();
    }

    private static void show(Node owner, String title, String header, String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(header);

        if(owner != null && owner.getScene() != null)
        {
            alert.initOwner(owner.getScene().getWindow());
        }

        alert.showAndWait();
    }
}
