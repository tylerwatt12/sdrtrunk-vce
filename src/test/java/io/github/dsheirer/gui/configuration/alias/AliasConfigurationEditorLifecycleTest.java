/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the JavaFX draft/selection lifecycle against the real schema-v4 SQLite store. */
@Tag("display-backed-javafx")
class AliasConfigurationEditorLifecycleTest
{
    @TempDir
    Path mTemporaryFolder;
    private ConfigurationManager mManager;
    private AliasConfigurationEditor mEditor;

    @BeforeAll
    static void startJavaFx() throws Exception
    {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "A display-backed JavaFX toolkit is required for this control lifecycle test");
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void cleanup() throws Exception
    {
        if(mEditor != null)
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                itemEditor(mEditor).dispose();
                return null;
            });
        }

        if(mManager != null)
        {
            MyEventBus.getGlobalEventBus().unregister(mManager.getChannelProcessingManager());
        }
    }

    @Test
    void cloneStaysDetachedUntilSaveAndThenSelectsOneDurableRow() throws Exception
    {
        Fixture fixture = createFixture(List.of(alias("Original", 100), alias("Neighbor", 200)));
        Alias original = mManager.getAliasModel().getAliases().stream()
            .filter(alias -> "Original".equals(alias.getName())).findFirst().orElseThrow();

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(original);
            return null;
        });
        JavaFxTestSupport.drainEvents();

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().clearSelection();
            table(mEditor).getSelectionModel().select(original);
            cloneButton(mEditor).fire();
            Platform.runLater(() -> fireOpenDialogButton(ButtonType.NO));
            return null;
        });
        JavaFxTestSupport.drainEvents();

        AliasItemEditor itemEditor = itemEditor(mEditor);
        Alias draft = JavaFxTestSupport.onFxThread(itemEditor::getItem);
        assertEquals(Alias.UNASSIGNED_ID, draft.getId());
        assertEquals(2, mManager.getAliasModel().getAliases().size());
        assertEquals(2, loadAliases(fixture.database()).size());
        assertTrue(JavaFxTestSupport.onFxThread(() -> itemEditor.modifiedProperty().get()));

        JavaFxTestSupport.onFxThread(() ->
        {
            itemEditor.getNameField().setText("Cloned and Edited");
            itemEditor.save();
            return null;
        });
        JavaFxTestSupport.drainEvents();

        List<Alias> live = mManager.getAliasModel().getAliases();
        List<Alias> stored = loadAliases(fixture.database());
        assertEquals(3, live.size());
        assertEquals(3, stored.size());
        assertEquals(3, live.stream().map(Alias::getId).collect(java.util.stream.Collectors.toSet()).size());
        assertEquals(3, stored.stream().map(Alias::getId).collect(java.util.stream.Collectors.toSet()).size());
        assertTrue(stored.stream().anyMatch(alias -> "Original".equals(alias.getName())));
        Alias clone = live.stream().filter(alias -> "Cloned and Edited".equals(alias.getName()))
            .findFirst().orElseThrow();
        assertNotEquals(Alias.UNASSIGNED_ID, clone.getId());
        assertSame(clone, JavaFxTestSupport.onFxThread(itemEditor::getItem));
        assertEquals(clone.getId(), JavaFxTestSupport.onFxThread(() ->
            table(mEditor).getSelectionModel().getSelectedItem().getId()));
    }

    @Test
    void sortChangingSaveKeepsTheNewlySelectedAlias() throws Exception
    {
        createFixture(List.of(alias("Alpha", 100), alias("Bravo", 200)));
        Alias alpha = liveAlias("Alpha");
        Alias bravo = liveAlias("Bravo");

        JavaFxTestSupport.onFxThread(() ->
        {
            TableView<Alias> table = table(mEditor);
            @SuppressWarnings("unchecked")
            TableColumn<Alias,String> nameColumn = (TableColumn<Alias,String>)table.getColumns().stream()
                .filter(column -> "Alias".equals(column.getText())).findFirst().orElseThrow();
            nameColumn.setSortType(TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll(nameColumn);
            table.getSelectionModel().select(alpha);
            return null;
        });
        JavaFxTestSupport.drainEvents();

        JavaFxTestSupport.onFxThread(() ->
        {
            itemEditor(mEditor).getNameField().setText("Zulu");
            TableView<Alias> table = table(mEditor);
            table.getSelectionModel().clearAndSelect(table.getItems().indexOf(bravo));
            Platform.runLater(() -> fireOpenDialogButton(ButtonType.YES));
            return null;
        });
        JavaFxTestSupport.drainEvents();

        Alias savedAlpha = mManager.getAliasModel().getAlias(alpha.getId());
        Alias selected = JavaFxTestSupport.onFxThread(() -> table(mEditor).getSelectionModel().getSelectedItem());
        assertEquals("Zulu", savedAlpha.getName());
        assertEquals(bravo.getId(), selected.getId());
        assertSame(selected, JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem()));
        assertTrue(loadAliases(mManager.getDatabasePath()).stream()
            .anyMatch(alias -> alias.getId() == alpha.getId() && "Zulu".equals(alias.getName())));
    }

    @Test
    void moveUsesTheCanonicalRowAfterSavingEdits() throws Exception
    {
        AliasListDefinition source = new AliasListDefinition("Source", AliasListFamily.P25);
        AliasListDefinition target = new AliasListDefinition("Target", AliasListFamily.P25);
        Alias original = alias("Original", 100);
        original.setAliasListDefinition(source);
        Fixture fixture = createFixture(List.of(original), List.of(source, target));
        Alias live = liveAlias("Original");

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(live);
            return null;
        });
        JavaFxTestSupport.drainEvents();

        JavaFxTestSupport.onFxThread(() ->
        {
            itemEditor(mEditor).getNameField().setText("Edited Before Move");
            AliasListDefinition liveTarget = mManager.getAliasModel().getAliasListDefinition("Target");
            AliasConfigurationEditor.MoveToAliasListItem move = mEditor.new MoveToAliasListItem(liveTarget);
            Platform.runLater(() -> fireOpenDialogButton(ButtonType.YES));
            move.fire();
            return null;
        });
        JavaFxTestSupport.drainEvents();

        List<Alias> stored = loadAliases(fixture.database());
        assertEquals(1, stored.size());
        assertEquals("Edited Before Move", stored.getFirst().getName());
        assertEquals("Target", stored.getFirst().getAliasListName());
        assertEquals("Edited Before Move", mManager.getAliasModel().getAlias(live.getId()).getName());
        assertEquals("Target", mManager.getAliasModel().getAlias(live.getId()).getAliasListName());
    }

    @Test
    void failedSaveRestoresTheEditedSelectionAndDraft() throws Exception
    {
        createFixture(List.of(alias("Alpha", 100), alias("Bravo", 200)));
        Alias alpha = liveAlias("Alpha");
        Alias bravo = liveAlias("Bravo");

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(alpha);
            return null;
        });
        JavaFxTestSupport.drainEvents();

        setBooleanField(mManager, "mExternalConfigurationOperation", true);

        try
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                itemEditor(mEditor).getNameField().setText("Unsaved Alpha");
                TableView<Alias> table = table(mEditor);
                table.getSelectionModel().clearAndSelect(table.getItems().indexOf(bravo));
                Platform.runLater(() ->
                {
                    fireOpenDialogButton(ButtonType.YES);
                    Platform.runLater(() -> fireOpenDialogButton(ButtonType.OK));
                });
                return null;
            });
            JavaFxTestSupport.drainEvents();

            assertEquals(alpha.getId(), JavaFxTestSupport.onFxThread(() ->
                table(mEditor).getSelectionModel().getSelectedItem().getId()));
            assertSame(alpha, JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem()));
            assertEquals("Unsaved Alpha", JavaFxTestSupport.onFxThread(() ->
                itemEditor(mEditor).getNameField().getText()));
            assertTrue(JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).modifiedProperty().get()));
            assertEquals("Alpha", mManager.getAliasModel().getAlias(alpha.getId()).getName());
        }
        finally
        {
            setBooleanField(mManager, "mExternalConfigurationOperation", false);
        }
    }

    @Test
    void directSaveFailureShowsOnePersistenceErrorAndKeepsTheDraft() throws Exception
    {
        createFixture(List.of(alias("Alpha", 100)));
        Alias alpha = liveAlias("Alpha");

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(alpha);
            return null;
        });
        JavaFxTestSupport.drainEvents();
        setBooleanField(mManager, "mExternalConfigurationOperation", true);
        AtomicBoolean errorShown = new AtomicBoolean();

        try
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                itemEditor(mEditor).getNameField().setText("Unsaved Alpha");
                Platform.runLater(() -> errorShown.set(fireOpenDialogButton(ButtonType.OK)));
                itemEditor(mEditor).save();
                return null;
            });
            JavaFxTestSupport.drainEvents();

            assertTrue(errorShown.get());
            assertSame(alpha, JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem()));
            assertEquals("Unsaved Alpha", JavaFxTestSupport.onFxThread(() ->
                itemEditor(mEditor).getNameField().getText()));
            assertTrue(JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).modifiedProperty().get()));
            assertEquals("Alpha", mManager.getAliasModel().getAlias(alpha.getId()).getName());
        }
        finally
        {
            setBooleanField(mManager, "mExternalConfigurationOperation", false);
        }
    }

    @Test
    void failedCloneSaveClearsTheDestinationSelectionAndKeepsTheDraft() throws Exception
    {
        Fixture fixture = createFixture(List.of(alias("Alpha", 100), alias("Bravo", 200)));
        Alias alpha = liveAlias("Alpha");
        Alias bravo = liveAlias("Bravo");

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(alpha);
            return null;
        });
        JavaFxTestSupport.drainEvents();
        JavaFxTestSupport.onFxThread(() ->
        {
            cloneButton(mEditor).fire();
            return null;
        });
        JavaFxTestSupport.drainEvents();
        Alias draft = JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem());
        assertEquals(Alias.UNASSIGNED_ID, draft.getId());

        setBooleanField(mManager, "mExternalConfigurationOperation", true);
        AtomicBoolean errorShown = new AtomicBoolean();

        try
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                itemEditor(mEditor).getNameField().setText("Unsaved Clone");
                TableView<Alias> table = table(mEditor);
                table.getSelectionModel().clearAndSelect(table.getItems().indexOf(bravo));
                Platform.runLater(() ->
                {
                    fireOpenDialogButton(ButtonType.YES);
                    Platform.runLater(() -> errorShown.set(fireOpenDialogButton(ButtonType.OK)));
                });
                return null;
            });
            JavaFxTestSupport.drainEvents();

            assertTrue(errorShown.get());
            assertNull(JavaFxTestSupport.onFxThread(() -> table(mEditor).getSelectionModel().getSelectedItem()));
            assertSame(draft, JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem()));
            assertEquals("Unsaved Clone", JavaFxTestSupport.onFxThread(() ->
                itemEditor(mEditor).getNameField().getText()));
            assertTrue(JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).modifiedProperty().get()));
            assertEquals(2, mManager.getAliasModel().getAliases().size());
            assertEquals(2, loadAliases(fixture.database()).size());
        }
        finally
        {
            setBooleanField(mManager, "mExternalConfigurationOperation", false);
        }
    }

    @Test
    void liveUnassignedImportEditPublishesOneDurableRow() throws Exception
    {
        Fixture fixture = createFixture(List.of(alias("Existing", 100), alias("Neighbor", 200)));
        AliasListDefinition definition = mManager.getAliasModel().getAliasListDefinition("County");
        Alias imported = alias("RadioReference Import", 401);
        imported.setAliasListDefinition(definition);
        setBooleanField(mManager, "mConfigurationLoading", true);

        try
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                mManager.getAliasModel().addAlias(imported);
                return null;
            });
        }
        finally
        {
            setBooleanField(mManager, "mConfigurationLoading", false);
        }

        assertEquals(Alias.UNASSIGNED_ID, imported.getId());
        assertEquals(3, mManager.getAliasModel().getAliases().size());
        assertEquals(2, loadAliases(fixture.database()).size());

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(imported);
            return null;
        });
        JavaFxTestSupport.drainEvents();
        assertSame(imported, JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem()));

        JavaFxTestSupport.onFxThread(() ->
        {
            itemEditor(mEditor).getNameField().setText("RadioReference Import Edited");
            itemEditor(mEditor).save();
            return null;
        });
        JavaFxTestSupport.drainEvents();

        List<Alias> live = mManager.getAliasModel().getAliases();
        List<Alias> stored = loadAliases(fixture.database());
        assertEquals(3, live.size());
        assertEquals(3, stored.size());
        assertEquals(3, new HashSet<>(live.stream().map(Alias::getId).toList()).size());
        assertEquals(3, new HashSet<>(stored.stream().map(Alias::getId).toList()).size());
        assertTrue(live.stream().allMatch(alias -> alias.getId() > Alias.UNASSIGNED_ID));
        assertTrue(stored.stream().allMatch(alias -> alias.getId() > Alias.UNASSIGNED_ID));
        assertTrue(live.stream().noneMatch(alias -> alias == imported));
        assertEquals(1, live.stream()
            .filter(alias -> "RadioReference Import Edited".equals(alias.getName())).count());
        assertEquals(1, stored.stream()
            .filter(alias -> "RadioReference Import Edited".equals(alias.getName())).count());
    }

    @Test
    void liveUnassignedImportCanSaveThenClone() throws Exception
    {
        Fixture fixture = createFixture(List.of(alias("Existing", 100)));
        Alias imported = alias("RadioReference Import", 401);
        imported.setAliasListDefinition(mManager.getAliasModel().getAliasListDefinition("County"));
        setBooleanField(mManager, "mConfigurationLoading", true);

        try
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                mManager.getAliasModel().addAlias(imported);
                return null;
            });
        }
        finally
        {
            setBooleanField(mManager, "mConfigurationLoading", false);
        }

        JavaFxTestSupport.onFxThread(() ->
        {
            table(mEditor).getSelectionModel().select(imported);
            return null;
        });
        JavaFxTestSupport.drainEvents();

        JavaFxTestSupport.onFxThread(() ->
        {
            itemEditor(mEditor).getNameField().setText("Saved Before Clone");
            Platform.runLater(() -> fireOpenDialogButton(ButtonType.YES));
            cloneButton(mEditor).fire();
            return null;
        });
        JavaFxTestSupport.drainEvents();

        Alias draft = JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).getItem());
        List<Alias> live = mManager.getAliasModel().getAliases();
        List<Alias> stored = loadAliases(fixture.database());
        assertEquals(Alias.UNASSIGNED_ID, draft.getId());
        assertEquals("Saved Before Clone", draft.getName());
        assertEquals(2, live.size());
        assertEquals(2, stored.size());
        assertTrue(live.stream().noneMatch(alias -> alias == imported || alias == draft));
        assertEquals(1, live.stream().filter(alias -> "Saved Before Clone".equals(alias.getName())).count());
        assertEquals(1, stored.stream().filter(alias -> "Saved Before Clone".equals(alias.getName())).count());
        assertTrue(JavaFxTestSupport.onFxThread(() -> itemEditor(mEditor).modifiedProperty().get()));
        assertNull(JavaFxTestSupport.onFxThread(() -> table(mEditor).getSelectionModel().getSelectedItem()));
    }

    @Test
    void multiSelectionMoveReplacesLiveUnassignedImportsWithoutDuplicates() throws Exception
    {
        AliasListDefinition source = new AliasListDefinition("Source", AliasListFamily.P25);
        AliasListDefinition target = new AliasListDefinition("Target", AliasListFamily.P25);
        Alias persisted = alias("Persisted", 100);
        persisted.setAliasListDefinition(source);
        Fixture fixture = createFixture(List.of(persisted), List.of(source, target));
        Alias livePersisted = liveAlias("Persisted");
        AliasListDefinition liveSource = mManager.getAliasModel().getAliasListDefinition("Source");
        Alias importedOne = alias("Imported One", 200);
        Alias importedTwo = alias("Imported Two", 300);
        importedOne.setAliasListDefinition(liveSource);
        importedTwo.setAliasListDefinition(liveSource);
        setBooleanField(mManager, "mConfigurationLoading", true);

        try
        {
            JavaFxTestSupport.onFxThread(() ->
            {
                mManager.getAliasModel().addAliases(List.of(importedOne, importedTwo));
                return null;
            });
        }
        finally
        {
            setBooleanField(mManager, "mConfigurationLoading", false);
        }

        assertEquals(3, mManager.getAliasModel().getAliases().size());
        assertEquals(1, loadAliases(fixture.database()).size());

        JavaFxTestSupport.onFxThread(() ->
        {
            TableView<Alias> table = table(mEditor);
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(livePersisted);
            table.getSelectionModel().select(importedOne);
            table.getSelectionModel().select(importedTwo);
            return null;
        });
        JavaFxTestSupport.drainEvents();
        assertEquals(3, JavaFxTestSupport.onFxThread(() ->
            table(mEditor).getSelectionModel().getSelectedItems().size()));

        JavaFxTestSupport.onFxThread(() ->
        {
            AliasListDefinition liveTarget = mManager.getAliasModel().getAliasListDefinition("Target");
            AliasConfigurationEditor.MoveToAliasListItem move = mEditor.new MoveToAliasListItem(liveTarget);
            move.fire();
            return null;
        });
        JavaFxTestSupport.drainEvents();

        List<Alias> live = mManager.getAliasModel().getAliases();
        List<Alias> stored = loadAliases(fixture.database());
        assertEquals(3, live.size());
        assertEquals(3, stored.size());
        assertEquals(3, new HashSet<>(live.stream().map(Alias::getId).toList()).size());
        assertEquals(3, new HashSet<>(stored.stream().map(Alias::getId).toList()).size());
        assertTrue(live.stream().allMatch(alias -> alias.getId() > Alias.UNASSIGNED_ID));
        assertTrue(stored.stream().allMatch(alias -> alias.getId() > Alias.UNASSIGNED_ID));
        assertTrue(live.stream().allMatch(alias -> "Target".equals(alias.getAliasListName())));
        assertTrue(stored.stream().allMatch(alias -> "Target".equals(alias.getAliasListName())));
        assertTrue(live.stream().noneMatch(alias -> alias == importedOne || alias == importedTwo));
    }

    @Test
    void identifierColumnSortsTalkgroupsNumerically() throws Exception
    {
        createFixture(List.of(alias("One Hundred", 100), alias("Two", 2), alias("Ten", 10)));

        List<Integer> sorted = JavaFxTestSupport.onFxThread(() ->
        {
            TableView<Alias> table = table(mEditor);
            TableColumn<Alias,?> identifier = table.getColumns().stream()
                .filter(column -> "Identifier".equals(column.getText())).findFirst().orElseThrow();
            identifier.setSortType(TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll(identifier);
            table.sort();
            return table.getItems().stream()
                .map(alias -> ((Talkgroup)alias.getMatchIdentifier()).getValue()).toList();
        });

        assertEquals(List.of(2, 10, 100), sorted);
    }

    @Test
    void identifierViewSortsNumericMatcherTypesByValue() throws Exception
    {
        createFixture(List.of(
            alias("Talkgroup One Hundred", new Talkgroup(Protocol.APCO25, 100)),
            alias("Talkgroup Two", new Talkgroup(Protocol.APCO25, 2)),
            alias("Talkgroup Ten", new Talkgroup(Protocol.APCO25, 10)),
            alias("Talkgroup Range One Hundred", new TalkgroupRange(Protocol.APCO25, 100, 109)),
            alias("Talkgroup Range Two", new TalkgroupRange(Protocol.APCO25, 2, 9)),
            alias("Talkgroup Range Ten", new TalkgroupRange(Protocol.APCO25, 10, 19)),
            alias("Radio One Hundred", new Radio(Protocol.APCO25, 100)),
            alias("Radio Two", new Radio(Protocol.APCO25, 2)),
            alias("Radio Ten", new Radio(Protocol.APCO25, 10)),
            alias("Radio Range One Hundred", new RadioRange(Protocol.APCO25, 100, 109)),
            alias("Radio Range Two", new RadioRange(Protocol.APCO25, 2, 9)),
            alias("Radio Range Ten", new RadioRange(Protocol.APCO25, 10, 19))));

        AliasViewByIdentifierEditor identifierEditor = JavaFxTestSupport.onFxThread(() ->
        {
            AliasViewByIdentifierEditor editor = new AliasViewByIdentifierEditor(mManager,
                new SimpleBooleanProperty(true));
            new Scene(editor, 900, 700);
            editor.applyCss();
            editor.layout();
            return editor;
        });

        assertEquals(List.of(2, 10, 100), sortedIdentifiers(identifierEditor, AliasIDType.TALKGROUP).stream()
            .map(identifier -> ((Talkgroup)identifier).getValue()).toList());
        assertEquals(List.of(2, 10, 100), sortedIdentifiers(identifierEditor, AliasIDType.TALKGROUP_RANGE).stream()
            .map(identifier -> ((TalkgroupRange)identifier).getMinTalkgroup()).toList());
        assertEquals(List.of(2, 10, 100), sortedIdentifiers(identifierEditor, AliasIDType.RADIO_ID).stream()
            .map(identifier -> ((Radio)identifier).getValue()).toList());
        assertEquals(List.of(2, 10, 100), sortedIdentifiers(identifierEditor, AliasIDType.RADIO_ID_RANGE).stream()
            .map(identifier -> ((RadioRange)identifier).getMinRadio()).toList());
    }

    private Fixture createFixture(List<Alias> aliases) throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        aliases.forEach(alias -> alias.setAliasListDefinition(definition));
        return createFixture(aliases, List.of(definition));
    }

    private Fixture createFixture(List<Alias> aliases, List<AliasListDefinition> definitions) throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data-" + System.nanoTime());
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationState state = new ConfigurationState();
        state.setAliases(aliases);
        state.setAliasListDefinitions(definitions);
        new ConfigurationSnapshotDatabaseStore(database).replace(state);

        Dependencies dependencies = JavaFxTestSupport.withPortableDataRoot(dataRoot,
            () -> new Dependencies(new TestUserPreferences(dataRoot), new IconModel()));
        mManager = new ConfigurationManager(dependencies.preferences(), null, new AliasModel(), null,
            dependencies.iconModel());
        mManager.init();
        mEditor = JavaFxTestSupport.onFxThread(() ->
        {
            AliasConfigurationEditor editor = new AliasConfigurationEditor(mManager, dependencies.preferences());
            new Scene(editor, 1200, 900);
            editor.applyCss();
            editor.layout();
            return editor;
        });
        JavaFxTestSupport.drainEvents();
        return new Fixture(database);
    }

    private Alias liveAlias(String name)
    {
        return mManager.getAliasModel().getAliases().stream()
            .filter(alias -> name.equals(alias.getName())).findFirst().orElseThrow();
    }

    private static boolean fireOpenDialogButton(ButtonType buttonType)
    {
        for(Window window: Window.getWindows())
        {
            if(window.isShowing() && window.getScene() != null)
            {
                DialogPane dialogPane = window.getScene().getRoot() instanceof DialogPane pane ? pane :
                    (DialogPane)window.getScene().getRoot().lookup(".dialog-pane");

                if(dialogPane != null && dialogPane.lookupButton(buttonType) instanceof Button button)
                {
                    button.fire();
                    return true;
                }
            }
        }

        return false;
    }

    private static Alias alias(String name, int talkgroup)
    {
        return alias(name, new Talkgroup(Protocol.APCO25, talkgroup));
    }

    private static Alias alias(String name, AliasID identifier)
    {
        Alias alias = new Alias(name);
        alias.setMatchIdentifier(identifier);
        return alias;
    }

    private static List<AliasID> sortedIdentifiers(AliasViewByIdentifierEditor editor, AliasIDType type)
        throws Exception
    {
        return JavaFxTestSupport.onFxThread(() ->
        {
            @SuppressWarnings("unchecked")
            ComboBox<AliasIDType> typeComboBox = field(editor, "mAliasIDTypeComboBox", ComboBox.class);
            @SuppressWarnings("unchecked")
            TableView<AliasViewByIdentifierEditor.AliasAndIdentifier> table =
                field(editor, "mAliasAndIdentifierTableView", TableView.class);
            typeComboBox.getSelectionModel().select(type);
            TableColumn<AliasViewByIdentifierEditor.AliasAndIdentifier,?> identifierColumn = table.getColumns().stream()
                .filter(column -> "Identifier".equals(column.getText())).findFirst().orElseThrow();
            identifierColumn.setSortType(TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll(identifierColumn);
            table.sort();
            return table.getItems().stream().map(AliasViewByIdentifierEditor.AliasAndIdentifier::getAliasIdentifier)
                .toList();
        });
    }

    private static List<Alias> loadAliases(Path database) throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        List<Alias> aliases = store.loadAliases(definitions);
        assertEquals(aliases.size(), new HashSet<>(aliases.stream().map(Alias::getId).toList()).size());
        return aliases;
    }

    @SuppressWarnings("unchecked")
    private static TableView<Alias> table(AliasConfigurationEditor editor)
    {
        return field(editor, "mAliasTableView", TableView.class);
    }

    private static Button cloneButton(AliasConfigurationEditor editor)
    {
        return field(editor, "mCloneAliasButton", Button.class);
    }

    private static AliasItemEditor itemEditor(AliasConfigurationEditor editor)
    {
        return field(editor, "mAliasItemEditor", AliasItemEditor.class);
    }

    private static <T> T field(Object target, String name, Class<T> type)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        }
        catch(ReflectiveOperationException e)
        {
            throw new AssertionError("Unable to read field " + name, e);
        }
    }

    private static void setBooleanField(Object target, String name, boolean value)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(target, value);
        }
        catch(ReflectiveOperationException e)
        {
            throw new AssertionError("Unable to set field " + name, e);
        }
    }

    private record Dependencies(UserPreferences preferences, IconModel iconModel) {}

    private record Fixture(Path database) {}

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(preferenceType -> {})
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return dataRoot;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }
}
