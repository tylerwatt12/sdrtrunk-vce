/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the add/save/list-switch regression through real JavaFX controls and the SQLite boundary. */
class AliasConfigurationEditorLifecycleTest
{
    @TempDir
    Path mTemporaryFolder;

    @BeforeAll
    static void startJavaFx() throws Exception
    {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "A display-backed JavaFX toolkit is required for this control lifecycle test");
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void newSaveAndAliasListSwitchKeepOneCanonicalRow() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("alias-editor-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Dependencies dependencies = JavaFxTestSupport.withPortableDataRoot(dataRoot,
            () -> new Dependencies(new TestUserPreferences(dataRoot), new IconModel()));
        ConfigurationManager manager = new ConfigurationManager(dependencies.preferences(), null,
            new AliasModel(), null, dependencies.iconModel());
        AliasConfigurationEditor editor = null;

        try
        {
            manager.init();
            AliasAdministrationService service = manager.getAliasAdministrationService();
            service.createAliasList("List A", AliasListFamily.P25);
            service.createAliasList("List B", AliasListFamily.P25);

            editor = JavaFxTestSupport.onFxThread(() ->
            {
                AliasConfigurationEditor created = new AliasConfigurationEditor(manager, dependencies.preferences());
                new Scene(created, 1200, 900);
                created.applyCss();
                created.layout();
                return created;
            });
            AliasConfigurationEditor activeEditor = editor;

            DraftState draft = JavaFxTestSupport.onFxThread(() ->
            {
                ComboBox<String> selector = aliasListSelector(activeEditor);
                TableView<Alias> table = aliasTable(activeEditor);
                Button newAlias = control(activeEditor, "#new-alias-button", Button.class);
                Button save = control(activeEditor, "#alias-save-button", Button.class);
                selector.getSelectionModel().select("List A");
                assertFalse(newAlias.isDisabled());
                newAlias.fire();
                AliasItemEditor itemEditor = itemEditor(activeEditor);
                return new DraftState(itemEditor.getItem().getId(), manager.getAliasModel().getAliases().size(),
                    table.getItems().size(), save.isDisabled());
            });

            assertEquals(Alias.UNASSIGNED_ID, draft.aliasId());
            assertEquals(0, draft.modelRows());
            assertEquals(0, draft.tableRows());
            assertFalse(draft.saveDisabled(), "New detached drafts must be immediately saveable");
            assertTrue(loadAliases(database).isEmpty(), "New must not insert a SQLite row before Save");

            JavaFxTestSupport.onFxThread(() ->
            {
                TextField name = control(activeEditor, "#alias-name-field", TextField.class);
                Button save = control(activeEditor, "#alias-save-button", Button.class);
                name.setText("Only Alias");
                assertFalse(save.isDisabled());
                save.fire();
                return null;
            });
            JavaFxTestSupport.drainEvents();

            List<Alias> stored = loadAliases(database);
            assertEquals(1, stored.size());
            long storedId = stored.getFirst().getId();
            assertTrue(storedId > Alias.UNASSIGNED_ID);

            RowState saved = rowState(activeEditor, manager);
            assertEquals("List A", saved.selectedList());
            assertEquals(List.of(storedId), saved.modelIds());
            assertEquals(List.of(storedId), saved.tableIds());

            JavaFxTestSupport.onFxThread(() ->
            {
                aliasListSelector(activeEditor).getSelectionModel().select("List B");
                return null;
            });
            JavaFxTestSupport.drainEvents();
            RowState listB = rowState(activeEditor, manager);
            assertEquals("List B", listB.selectedList());
            assertEquals(List.of(storedId), listB.modelIds());
            assertTrue(listB.tableIds().isEmpty());

            JavaFxTestSupport.onFxThread(() ->
            {
                aliasListSelector(activeEditor).getSelectionModel().select("List A");
                return null;
            });
            JavaFxTestSupport.drainEvents();
            RowState listA = rowState(activeEditor, manager);
            assertEquals("List A", listA.selectedList());
            assertEquals(List.of(storedId), listA.modelIds());
            assertEquals(List.of(storedId), listA.tableIds());
            assertEquals(1, loadAliases(database).size());
        }
        finally
        {
            if(editor != null)
            {
                AliasConfigurationEditor activeEditor = editor;
                JavaFxTestSupport.onFxThread(() ->
                {
                    itemEditor(activeEditor).dispose();
                    return null;
                });
            }

            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static RowState rowState(AliasConfigurationEditor editor, ConfigurationManager manager) throws Exception
    {
        return JavaFxTestSupport.onFxThread(() ->
        {
            ComboBox<String> selector = aliasListSelector(editor);
            TableView<Alias> table = aliasTable(editor);
            return new RowState(selector.getSelectionModel().getSelectedItem(),
                manager.getAliasModel().getAliases().stream().map(Alias::getId).toList(),
                table.getItems().stream().map(Alias::getId).toList());
        });
    }

    private static List<Alias> loadAliases(Path database) throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        return store.loadAliases(definitions);
    }

    private static AliasItemEditor itemEditor(AliasConfigurationEditor editor)
    {
        return editor.getItems().stream().filter(AliasItemEditor.class::isInstance)
            .map(AliasItemEditor.class::cast).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> aliasListSelector(AliasConfigurationEditor editor)
    {
        return (ComboBox<String>)requiredNode(editor, "#alias-list-selector");
    }

    @SuppressWarnings("unchecked")
    private static TableView<Alias> aliasTable(AliasConfigurationEditor editor)
    {
        return (TableView<Alias>)requiredNode(editor, "#alias-table");
    }

    private static <T> T control(AliasConfigurationEditor editor, String selector, Class<T> type)
    {
        return type.cast(requiredNode(editor, selector));
    }

    private static Object requiredNode(AliasConfigurationEditor editor, String selector)
    {
        Object node = editor.lookup(selector);

        if(node == null)
        {
            throw new AssertionError("Missing JavaFX control " + selector);
        }

        return node;
    }

    private record Dependencies(UserPreferences preferences, IconModel iconModel) {}

    private record DraftState(long aliasId, int modelRows, int tableRows, boolean saveDisabled) {}

    private record RowState(String selectedList, List<Long> modelIds, List<Long> tableIds) {}

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
