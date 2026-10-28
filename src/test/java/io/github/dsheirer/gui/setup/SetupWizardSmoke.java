package io.github.dsheirer.gui.setup;

import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Manual graphical smoke harness. Finishing NEVER constructs a receiver or starts channels. */
public final class SetupWizardSmoke
{
    private static final String BACKUP_ACKNOWLEDGMENT = "I have backed up my existing VCE installation";

    public static void main(String[] args) throws Exception
    {
        if(args.length!=1) throw new IllegalArgumentException("Supply a disposable data root");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("wizard-smoke-"))
            throw new IllegalArgumentException("Use a wizard-smoke- disposable directory");
        Files.createDirectories(root);
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY,root.toString());
        String mode = System.getProperty("wizard.exercise", "");
        if(mode.startsWith("upgrade-"))
        {
            if(mode.equals("upgrade-catalog-failure"))
            {
                Path database = root.resolve("database/sdrtrunk.sqlite");
                io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(database);
                io.github.dsheirer.database.InitialAdminSetup.provision(database, "smoke-only-password".toCharArray());
                var complete = new SetupProgress(true, false);
                complete.set(SetupStep.HARDWARE, SetupProgress.State.DEFERRED);
                complete.save(database);
                createOlderCatalog(root);
                Files.setPosixFilePermissions(root.resolve("database/managed-recordings.sqlite"),
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
            }
            else if(mode.equals("upgrade-configuration-only") || mode.equals("upgrade-full"))
                io.github.dsheirer.database.upgrade.Format60TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));
            else io.github.dsheirer.database.upgrade.Format30TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));
            if(mode.equals("upgrade-with-catalog")) createOlderCatalog(root);
        }
        if(mode.startsWith("transfer-")) prepareTransferSource(root,mode);
        if(mode.equals("import-theme"))
        {
            Path source = transferSource(root);
            Path sourceDatabase = source.resolve("database/sdrtrunk.sqlite");
            io.github.dsheirer.database.upgrade.Format64TestDatabase.create(sourceDatabase);
            seedDarkDesktopPreference(sourceDatabase);
            System.setProperty("wizard.source", source.toString());
        }
        if(mode.equals("xml-only")) Files.writeString(root.resolveSibling(root.getFileName() + "-playlist.xml"),
            "<playlist version=\"4\"><alias name=\"Disposable XML alias\" list=\"Smoke\"><id type=\"talkgroup\" " +
                "protocol=\"DMR\" value=\"200\"/></alias></playlist>");
        if(!System.getProperty("wizard.exercise", "").isBlank())
        {
            Thread driver=new Thread(()-> {
                try { exercise(root); }
                catch(Throwable e) { e.printStackTrace(); System.exit(2); }
            },"wizard smoke driver");
            driver.setDaemon(true); driver.start();
        }
        int status = 0;
        try
        {
            var result=SetupWizard.run(new String[]{"--setup-wizard"},root,null);
            if(result!=null) result.lock().close();
            if(mode.startsWith("upgrade-") && !SetupProgress.read(root.resolve("database/sdrtrunk.sqlite")).isComplete())
                throw new AssertionError("Exiting an automatic update reopened completed setup");
            System.out.println("Wizard upgrade smoke passed: " + mode);
        }
        catch(Throwable failure) { status = 2; failure.printStackTrace(); }
        finally { SqlitePreferencesFactory.shutdown(); System.exit(status); }
    }

    private static void createOlderCatalog(Path root) throws Exception
    {
        Path catalog = root.resolve("database/managed-recordings.sqlite");
        try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + catalog);
            var statement = connection.createStatement())
        {
            for(String ddl: io.github.dsheirer.record.managed.ManagedRecordingSchema.ddlForFormat(1).values())
                statement.execute(ddl);
            statement.execute("INSERT INTO catalog_metadata(id,format_version,call_count,total_bytes) VALUES(1,1,0,0)");
            statement.execute("PRAGMA application_id=" + io.github.dsheirer.record.managed.ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=1");
        }
    }

    private static Path transferSource(Path root)
    {
        return root.resolveSibling(root.getFileName() + "-previous").resolve("data");
    }

    private static void prepareTransferSource(Path root,String mode) throws Exception
    {
        Path source = transferSource(root);
        if(mode.equals("transfer-current"))
            io.github.dsheirer.database.upgrade.Format64TestDatabase.create(source.resolve("database/sdrtrunk.sqlite"));
        else io.github.dsheirer.database.upgrade.Format62TestDatabase.create(source.resolve("database/sdrtrunk.sqlite"));
        createOlderCatalog(source);
        Files.createDirectories(source.resolve("recordings-managed"));
        Files.write(source.resolve("recordings-managed/disposable-call.wav"), new byte[]{1,2,3,4});
        Files.createDirectories(source.resolve("logs"));
        Files.writeString(source.resolve("logs/disposable.log"), "Disposable graphical smoke artifact");
    }

    /** A stopped, disposable import fixture retains its desktop preference independently of setup presentation. */
    private static void seedDarkDesktopPreference(Path database) throws Exception
    {
        requireDisposableSource(database);
        try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key,settings_json,updated_at_ms)
                VALUES('portable_java_preferences_v1',
                    '{"user/io/github/dsheirer/preference/application":{"ui.theme":"DARK","dark.mode":"true"}}',1)
                ON CONFLICT(key) DO UPDATE SET settings_json=json_set(application_settings.settings_json,
                    '$."user/io/github/dsheirer/preference/application"."ui.theme"','DARK',
                    '$."user/io/github/dsheirer/preference/application"."dark.mode"','true')
                """);
        }
    }

    private static void exerciseTransfer(SetupWizard wizard, Path root) throws Exception
    {
        Path source = transferSource(root), database = source.resolve("database/sdrtrunk.sqlite");
        byte[] before = Files.readAllBytes(database);
        long aliases = tableCount(database,"alias"), channels = tableCount(database,"configuration_channel");
        byte[] audio = Files.readAllBytes(source.resolve("recordings-managed/disposable-call.wav"));
        javax.swing.SwingUtilities.invokeAndWait(() -> select(wizard,"Upgrade existing VCE"));
        chooseSource(wizard,source); await(wizard,SetupStep.SOURCE);
        assertNoPreparedDatabase(root);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var content = descendants(wizard).stream().filter(MigrationContentChoices.class::isInstance)
                .map(MigrationContentChoices.class::cast).findFirst().orElseThrow();
            if(content.selectedContent()!=io.github.dsheirer.database.upgrade.MigrationContent.CONFIGURATION_ONLY)
                throw new AssertionError("Upgrade must begin with settings-only selected");
            if(backupChoice(wizard).isSelected() || wizard.getRootPane().getDefaultButton().isEnabled())
                throw new AssertionError("The manual backup acknowledgment did not gate the first large operation");
            if(!hasVisibleText(wizard,"The old installation will no longer contain that data"))
                throw new AssertionError("Upgrade review did not explain that the data folder moves");
            var history = descendants(content).stream().filter(javax.swing.JCheckBox.class::isInstance)
                .map(javax.swing.JCheckBox.class::cast).findFirst().orElseThrow();
            history.doClick(); history.doClick();
        });
        assertNoPreparedDatabase(root);
        if(!java.util.Arrays.equals(before,Files.readAllBytes(database)))
            throw new AssertionError("Reviewing transfer choices changed the source database");
        capture(wizard,root,"transfer-choices-before-preparation");
        java.awt.Dimension normalSize = wizard.getSize();
        javax.swing.SwingUtilities.invokeAndWait(() -> wizard.setSize(800,600));
        capture(wizard,root,"transfer-choices-small");
        javax.swing.SwingUtilities.invokeAndWait(() -> wizard.setSize(normalSize));
        confirmBackup(wizard); capture(wizard,root,"transfer-ready");
        if(System.getProperty("wizard.exercise").equals("transfer-review"))
        {
            assertLightWizard(wizard);
            assertNoPreparedDatabase(root);
            if(!java.util.Arrays.equals(before,Files.readAllBytes(database)))
                throw new AssertionError("Preview changed the selected database");
            click(wizard,"Exit setup"); awaitClosed(wizard);
            return;
        }
        clickPrimary(wizard); await(wizard,SetupStep.SOURCE);
        assertNoPreparedDatabase(root);
        if(!java.util.Arrays.equals(before,Files.readAllBytes(database)))
            throw new AssertionError("Bounded review changed the selected database");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            if(!wizard.getRootPane().getDefaultButton().getText().equals("Begin upgrade"))
                throw new AssertionError("Source review did not show the declared-effects confirmation");
            var content = descendants(wizard).stream().filter(MigrationContentChoices.class::isInstance)
                .map(MigrationContentChoices.class::cast).findFirst().orElseThrow();
            if(content.selectedContent()!=io.github.dsheirer.database.upgrade.MigrationContent.CONFIGURATION_ONLY)
                throw new AssertionError("Review changed the selected history choice");
        });
        capture(wizard,root,"transfer-declared-effects");
        clickPrimary(wizard); await(wizard,SetupStep.SOURCE);
        if(!hasVisibleText(wizard,"Update the list of saved recordings"))
            throw new AssertionError("Transferred older recording catalog was not offered for update");
        if(!backupChoice(wizard).isSelected())
            throw new AssertionError("Complete backup acknowledgment was lost after data transfer");
        capture(wizard,root,"transfer-recording-catalog");
        clickPrimary(wizard); awaitAfterSource(wizard);
        Path destination = root.resolve("database/sdrtrunk.sqlite");
        if(tableCount(destination,"alias")!=aliases || tableCount(destination,"configuration_channel")!=channels)
            throw new AssertionError("Settings-only transfer lost configured aliases or channels");
        if(eventCount(destination)!=0) throw new AssertionError("Settings-only transfer retained Activity history");
        if(Files.exists(source)) throw new AssertionError("Completed data transfer left the old data folder active");
        if(!java.util.Arrays.equals(audio,Files.readAllBytes(root.resolve("recordings-managed/disposable-call.wav"))))
            throw new AssertionError("Settings-only transfer changed a recording file");
        if(!Files.readString(root.resolve("logs/disposable.log")).equals("Disposable graphical smoke artifact"))
            throw new AssertionError("Settings-only transfer omitted a supporting profile file");
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("database/managed-recordings.sqlite"));
            var query=connection.createStatement();var rows=query.executeQuery("PRAGMA user_version"))
        {
            if(!rows.next() || rows.getInt(1)!=io.github.dsheirer.record.managed.ManagedRecordingSchema.CURRENT_FORMAT_VERSION)
                throw new AssertionError("Transferred Managed Recordings catalog was not updated");
        }
        if(Files.exists(root.resolve("database/backups")))
            throw new AssertionError("Manual-backup transfer created an automatic database backup");
        capture(wizard,root,"transfer-complete");
        click(wizard,"Exit setup"); awaitClosed(wizard);
    }

    private static void exerciseXml(SetupWizard wizard, Path root) throws Exception
    {
        Path playlist = root.resolveSibling(root.getFileName() + "-playlist.xml");
        byte[] before = Files.readAllBytes(playlist);
        javax.swing.SwingUtilities.invokeAndWait(() -> select(wizard,"Import XML playlist"));
        chooseSource(wizard,playlist); await(wizard,SetupStep.SOURCE);
        assertNoPreparedDatabase(root);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            if(descendants(wizard).stream().anyMatch(component -> component instanceof MigrationContentChoices ||
                component instanceof javax.swing.JCheckBox box && box.isShowing() &&
                    isBackupChoice(box)))
                throw new AssertionError("XML import shows irrelevant VCE upgrade choices");
            if(!wizard.getRootPane().getDefaultButton().isEnabled())
                throw new AssertionError("Checked XML playlist cannot be imported");
        });
        capture(wizard,root,"xml-review");
        clickPrimary(wizard); awaitAfterSource(wizard);
        if(tableCount(root.resolve("database/sdrtrunk.sqlite"),"alias")!=1)
            throw new AssertionError("XML import did not retain the playlist alias");
        if(!java.util.Arrays.equals(before,Files.readAllBytes(playlist)))
            throw new AssertionError("XML import changed its source playlist");
        capture(wizard,root,"xml-imported");
        click(wizard,"Exit setup"); awaitClosed(wizard);
    }

    private static void assertNoPreparedDatabase(Path root) throws Exception
    {
        if(Files.exists(root.resolve("database/sdrtrunk.sqlite")))
            throw new AssertionError("Source/options review created a destination database");
        try(var entries=Files.list(root.getParent()))
        {
            String prefix="."+root.getFileName();
            if(entries.anyMatch(path -> path.getFileName().toString().startsWith(prefix)))
                throw new AssertionError("Source/options review created private migration work before confirmation");
        }
    }

    private static void requireDisposableSource(Path selected)
    {
        for(Path candidate=selected.toAbsolutePath().normalize();candidate!=null;candidate=candidate.getParent())
            if(candidate.getFileName()!=null && candidate.getFileName().toString().startsWith("wizard-smoke-")) return;
        throw new IllegalArgumentException("Upgrade smoke sources must belong to a wizard-smoke- disposable directory");
    }

    private static long tableCount(Path database, String table) throws Exception
    {
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);
            var query=connection.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+table))
        { rows.next(); return rows.getLong(1); }
    }

    private static void exercise(Path root) throws Exception
    {
        SetupWizard wizard=null;
        for(int i=0;i<200 && wizard==null;i++)
        {
            for(java.awt.Window window:java.awt.Window.getWindows()) if(window instanceof SetupWizard candidate && candidate.isShowing()) wizard=candidate;
            Thread.sleep(50);
        }
        if(wizard==null) throw new AssertionError("Wizard not shown");
        SetupWizard target=wizard;
        String mode=System.getProperty("wizard.exercise");
        assertLightWizard(target);
        if(mode.startsWith("upgrade-"))
        {
            if(mode.equals("upgrade-catalog-failure")) exerciseCatalogFailure(target, root);
            else exerciseUpgrade(target, root, mode);
            return;
        }
        capture(target,root,"01-source");
        if(!mode.equals("resume")) assertSourceChoices(target);
        if(mode.equals("source-options"))
        {
            assertNoPreparedDatabase(root);
            click(target,"Exit setup"); awaitClosed(target); return;
        }
        if(mode.startsWith("transfer-"))
        {
            exerciseTransfer(target,root); return;
        }
        if(mode.equals("xml-only"))
        {
            exerciseXml(target,root); return;
        }
        if(mode.equals("fresh-only"))
        {
            assertNoPreparedDatabase(root);
            clickPrimary(target); await(target,SetupStep.ADMINISTRATOR);
            io.github.dsheirer.database.SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(root.resolve("database/sdrtrunk.sqlite"));
            capture(target,root,"fresh-administrator");
            click(target,"Exit setup"); awaitClosed(target); return;
        }
        if(mode.startsWith("import"))
        {
            String source=System.getProperty("wizard.source");
            if(Files.isDirectory(Path.of(source))) requireDisposableSource(Path.of(source));
            javax.swing.SwingUtilities.invokeAndWait(() -> select(target, source.toLowerCase(java.util.Locale.ROOT).endsWith(".xml") ?
                "Import XML playlist" : "Upgrade existing VCE"));
            chooseSource(target,Path.of(source)); await(target,SetupStep.SOURCE);
            if(mode.equals("import-only") && Files.isDirectory(Path.of(source)))
            {
                Path historyDatabase=io.github.dsheirer.database.upgrade.PreviousBuildLocator.resolveSelection(Path.of(source))
                    .orElseThrow().database();
                String estimate=io.github.dsheirer.database.upgrade.ApplicationMigrationEstimate.fullMigrationWarning(historyDatabase);
                javax.swing.SwingUtilities.invokeAndWait(() -> descendants(target).stream()
                    .filter(MigrationContentChoices.class::isInstance).map(MigrationContentChoices.class::cast).findFirst().ifPresent(choices -> {
                        if(choices.selectedContent()!=io.github.dsheirer.database.upgrade.MigrationContent.CONFIGURATION_ONLY)
                            throw new AssertionError("Folder migration must start without history");
                        var keep=descendants(choices).stream().filter(javax.swing.JCheckBox.class::isInstance)
                            .map(javax.swing.JCheckBox.class::cast).findFirst().orElseThrow();
                        keep.doClick();
                        if(!hasVisibleText(target,estimate)) throw new AssertionError("Folder estimate did not use its database file");
                        keep.doClick();
                    }));
            }
            if(Files.isDirectory(Path.of(source)))
            {
                confirmBackup(target);
                clickPrimary(target); await(target,SetupStep.SOURCE);
                assertNoPreparedDatabase(root);
            }
            clickPrimary(target); awaitAfterSource(target);
            if(mode.equals("import-only") || mode.equals("import-theme"))
            {
                if(mode.equals("import-theme"))
                {
                    if(preferences(target).getApplicationPreference().getTheme()!=io.github.dsheirer.gui.theme.Theme.DARK)
                        throw new AssertionError("Import changed the stored desktop theme preference");
                    assertLightWizard(target);
                }
                capture(target,root,"import-complete");
                click(target,"Exit setup"); awaitClosed(target);
                return;
            }
            var imported=SetupProgress.read(root.resolve("database/sdrtrunk.sqlite"));
            if(!imported.isImported() || imported.get(SetupStep.ADMINISTRATOR)!=SetupProgress.State.CARRIED_OVER || imported.get(SetupStep.WEB)!=SetupProgress.State.CARRIED_OVER || imported.get(SetupStep.ACTIVITY)!=SetupProgress.State.CARRIED_OVER)
                throw new AssertionError("Imported valid settings were not carried over");
            await(target,SetupStep.JMBE);
        }
        else
        {
            clickPrimary(target); await(target,SetupStep.ADMINISTRATOR);
            capture(target,root,"02-admin");
            javax.swing.SwingUtilities.invokeAndWait(()-> {
                for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JPasswordField field && field.isShowing()) field.setText("smoke-only-password");
            });
            if(mode.equals("interrupt")) { click(target,"Exit setup"); return; }
            click(target,"Continue"); await(target,SetupStep.WEB);
            capture(target,root,"03-web");
            assertAndTypePort(target);
            capture(target,root,"03a-web-more-options");
            click(target,"▾  More options");
            if(mode.equals("feedback"))
            {
                click(target,"▸  About secure access");
                capture(target,root,"03b-web-expanded");
                click(target,"▾  About secure access");
            }
            click(target,"Continue"); await(target,SetupStep.JMBE);
            if(preferences(target).getApplicationPreference().getStatsWebServerPort()!=18091)
                throw new AssertionError("The typed port was not committed");
            if(mode.equals("web-options")) { click(target,"Exit setup"); awaitClosed(target); return; }
        }
        capture(target,root,"04-jmbe");
        if(mode.equals("jmbe-success")) exerciseJmbe(target,root); else click(target,"Set up later");
        await(target,SetupStep.RADIO_REFERENCE);
        capture(target,root,"05-radioreference");
        if(mode.equals("feedback")) exerciseFeedback(target,root);
        click(target,"Set up later");
        if(!mode.startsWith("import"))
        {
            await(target,SetupStep.ACTIVITY); capture(target,root,"06-activity");
            if(mode.equals("activity-off"))
            {
                javax.swing.SwingUtilities.invokeAndWait(()-> {
                    for(java.awt.Component component:descendants(target))
                        if(component instanceof javax.swing.JSpinner spinner && spinner.isShowing())
                            ((javax.swing.JSpinner.DefaultEditor)spinner.getEditor()).getTextField().setText("not a number");
                    select(target,"Off");
                    if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JSpinner && component.isShowing()))
                        throw new AssertionError("Off shows irrelevant retention controls");
                });
            }
            click(target,"Continue");
            if(mode.equals("activity-off") && (preferences(target).getApplicationPreference().isStatsLoggingEnabled() ||
                preferences(target).getApplicationPreference().getStatsLoggingRetentionDays()!=30))
                throw new AssertionError("Off did not preserve the dormant retention setting");
        }
        await(target,SetupStep.RECORDINGS);
        capture(target,root,"07-recordings");
        click(target,"Continue");
        await(target,SetupStep.HARDWARE);
        capture(target,root,"07-hardware");
        if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JButton button &&
            button.isShowing() && button.getText().equals("Skip discovery")))
            throw new AssertionError("Completed scan still has Skip discovery");
        click(target,"Continue"); await(target,SetupStep.CALIBRATION);
        capture(target,root,"08-benchmark"); click(target,"Use defaults for now"); await(target,SetupStep.REVIEW);
        capture(target,root,"09-review");
        javax.swing.SwingUtilities.invokeAndWait(() -> target.setSize(800,600));
        assertLightWizard(target);
        capture(target,root,"10-review-small");
        if(mode.equals("bind-failure"))
        {
            try(var occupied=new java.net.ServerSocket(18091,1,java.net.InetAddress.getLoopbackAddress()))
            {
                click(target,"Finish setup"); await(target,SetupStep.REVIEW);
                var banner=SetupWizard.class.getDeclaredField("danger"); banner.setAccessible(true);
                if(!((javax.swing.JTextArea)banner.get(target)).isVisible()) throw new AssertionError("Bind failure did not remain in the wizard");
                capture(target,root,"11-bind-error");
            }
        }
        var web=preferences(target).getApplicationPreference();
        boolean guideExpected=web.isStatsWebServerHttpsEnabled() &&
            web.getStatsWebServerCertificateMode()==io.github.dsheirer.preference.application.WebCertificateMode.AUTOMATIC;
        click(target,"Finish setup");
        if(guideExpected)
        {
            assertCertificateGuide(target,root);
            click(target,"Launch VCE & open browser");
        }
        awaitClosed(target);
        if(!SetupProgress.read(root.resolve("database/sdrtrunk.sqlite")).isComplete())
            throw new AssertionError("Final launch action did not complete setup");
    }

    private static void exerciseUpgrade(SetupWizard wizard, Path root, String mode) throws Exception
    {
        Path database = root.resolve("database/sdrtrunk.sqlite");
        byte[] source = Files.readAllBytes(database);
        long originalEvents = eventCount(database);
        javax.swing.JCheckBox[] backup = new javax.swing.JCheckBox[1];
        MigrationContentChoices[] content = new MigrationContentChoices[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            content[0] = descendants(wizard).stream().filter(MigrationContentChoices.class::isInstance)
                .map(MigrationContentChoices.class::cast).findFirst().orElseThrow();
            if(content[0].selectedContent() != io.github.dsheirer.database.upgrade.MigrationContent.CONFIGURATION_ONLY)
                throw new AssertionError("History must require an explicit selection");
            backup[0] = backupChoice(wizard);
            if(backup[0].isSelected()) throw new AssertionError("Manual backup acknowledgment must start unchecked");
            if(wizard.getRootPane().getDefaultButton().isEnabled())
                throw new AssertionError("Upgrade can start without a confirmed manual backup");
        });
        capture(wizard,root,"upgrade-configuration-ready");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            if(mode.equals("upgrade-full"))
                descendants(content[0]).stream().filter(javax.swing.JCheckBox.class::isInstance)
                    .map(javax.swing.JCheckBox.class::cast).findFirst().orElseThrow().doClick();
        });
        if(!java.util.Arrays.equals(source,Files.readAllBytes(database)))
            throw new AssertionError("Reviewing choices changed the database");
        if(mode.equals("upgrade-configuration-only") &&
            content[0].selectedContent()!=io.github.dsheirer.database.upgrade.MigrationContent.CONFIGURATION_ONLY)
            throw new AssertionError("Configuration-only selection did not stick");
        if(mode.equals("upgrade-full") &&
            content[0].selectedContent()!=io.github.dsheirer.database.upgrade.MigrationContent.FULL)
            throw new AssertionError("Explicit full-history selection did not stick");
        confirmBackup(wizard);
        capture(wizard,root,"upgrade-ready");
        clickPrimary(wizard);
        if(mode.equals("upgrade-with-catalog"))
        {
            await(wizard,SetupStep.SOURCE);
            var reportField=SetupWizard.class.getDeclaredField("migrationReport"); reportField.setAccessible(true);
            String mainReport=(String)reportField.get(wizard);
            if(mainReport.isBlank()) throw new AssertionError("Application update report was not retained");
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                if(!backupChoice(wizard).isSelected())
                    throw new AssertionError("The complete manual-backup acknowledgment was lost between database and catalog updates");
            });
            capture(wizard,root,"upgrade-recordings-ready");
            clickPrimary(wizard);
            awaitAfterSource(wizard);
            String combined=(String)reportField.get(wizard);
            if(!combined.startsWith(mainReport) || !combined.contains("Managed Recordings"))
                throw new AssertionError("Consecutive updates lost one of their reports");
        }
        awaitAfterSource(wizard);
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database))
        {
            if(io.github.dsheirer.database.upgrade.DatabaseFormatCatalog.requireCurrent(connection).version()!=
                io.github.dsheirer.database.upgrade.DatabaseFormatCatalog.CURRENT_VERSION)
                throw new AssertionError("Update did not reach the current format");
        }
        if(mode.equals("upgrade-full") && eventCount(database)!=originalEvents ||
            mode.equals("upgrade-configuration-only") && eventCount(database)!=0)
            throw new AssertionError("The selected history choice was not applied");
        if(Files.exists(root.resolve("database/backups")))
            throw new AssertionError("Manual-backup update created an automatic backup");
        capture(wizard,root,"upgrade-complete");
        //Use the normal navigation control after commit; the update must not be offered again.
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try
            {
                var field=SetupWizard.class.getDeclaredField("steps"); field.setAccessible(true);
                ((java.util.Map<SetupStep,javax.swing.JButton>)field.get(wizard)).get(SetupStep.SOURCE).doClick();
            }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        await(wizard,SetupStep.SOURCE);
        if(descendants(wizard).stream().anyMatch(c -> c instanceof javax.swing.JCheckBox box && box.isShowing() &&
            isBackupChoice(box)))
            throw new AssertionError("Back offered to repeat a committed update");
        if(descendants(wizard).stream().anyMatch(c -> c instanceof javax.swing.JButton button && button.isShowing() &&
            button.getText().equals("Choose different settings")))
            throw new AssertionError("Back offered another import after commit");
        click(wizard,"Exit setup"); awaitClosed(wizard);
    }

    private static javax.swing.JCheckBox backupChoice(SetupWizard wizard)
    {
        return descendants(wizard).stream().filter(c -> c instanceof javax.swing.JCheckBox box && box.isShowing() &&
            isBackupChoice(box)).map(javax.swing.JCheckBox.class::cast).findFirst().orElseThrow();
    }

    private static boolean isBackupChoice(javax.swing.JCheckBox box)
    {
        String name = box.getAccessibleContext().getAccessibleName();
        return name != null && name.startsWith(BACKUP_ACKNOWLEDGMENT);
    }

    private static void assertLightWizard(SetupWizard wizard) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            if(!(javax.swing.UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatLightLaf))
                throw new AssertionError("The wizard must keep its single light appearance");
            java.awt.Color background = javax.swing.UIManager.getColor("Panel.background");
            if(background == null || background.getRed() + background.getGreen() + background.getBlue() < 384)
                throw new AssertionError("The wizard retained a dark panel palette");
            if(descendants(wizard).stream().anyMatch(component -> component instanceof javax.swing.JButton button &&
                ("Light mode".equals(button.getText()) || "Dark mode".equals(button.getText()))))
                throw new AssertionError("Setup still offers a theme switch");
            for(java.awt.Component component: descendants(wizard))
            {
                if(!(component instanceof javax.swing.JCheckBox backup) || !backup.isShowing() ||
                    !isBackupChoice(backup)) continue;
                java.awt.Font body = WizardStyles.bodyFont(), font = backup.getFont();
                if(!font.getFamily().equals(body.getFamily()) || font.getSize2D() != body.getSize2D() || !font.isBold())
                    throw new AssertionError("Backup acknowledgment must use the common body font in bold");
                if(!backup.getForeground().equals(WizardNotice.accent(WizardNotice.Tone.ERROR)))
                    throw new AssertionError("Backup acknowledgment must use the red attention color");
                if(!"I have backed up my existing VCE installation, and it is currently not running.".equals(
                    backup.getAccessibleContext().getAccessibleName()))
                    throw new AssertionError("Backup acknowledgment must include both backup and stopped-installation confirmation");
            }
        });
    }

    private static void confirmBackup(SetupWizard wizard) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var acknowledgment = backupChoice(wizard);
            var primary = wizard.getRootPane().getDefaultButton();
            if(acknowledgment.isSelected()) return;
            if(primary.isEnabled()) throw new AssertionError("Unconfirmed manual backup did not gate the upgrade");
            acknowledgment.doClick();
            if(!primary.isEnabled()) throw new AssertionError("Confirming the manual backup did not enable the upgrade");
            acknowledgment.doClick();
            if(primary.isEnabled()) throw new AssertionError("Removing acknowledgment left the upgrade enabled");
            acknowledgment.doClick();
        });
    }

    private static long eventCount(Path database) throws Exception
    {
        Path activity=io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database);
        Path history=Files.isRegularFile(activity) ? activity : database;
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+history);
            var query=connection.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM receiver_activity_event"))
        { rows.next(); return rows.getLong(1); }
    }

    private static void clickPrimary(SetupWizard wizard) throws Exception
    { javax.swing.SwingUtilities.invokeAndWait(() -> wizard.getRootPane().getDefaultButton().doClick()); }

    private static void awaitAfterSource(SetupWizard wizard) throws Exception
    {
        var step=SetupWizard.class.getDeclaredField("step"); step.setAccessible(true);
        var busy=SetupWizard.class.getDeclaredField("busy"); busy.setAccessible(true);
        for(int attempt=0;attempt<400;attempt++)
        {
            boolean[] ready={false};
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try { ready[0]=step.get(wizard)!=SetupStep.SOURCE && !busy.getBoolean(wizard); }
                catch(Exception e) { throw new RuntimeException(e); }
            });
            if(ready[0]) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Setup did not continue after the source operation");
    }

    private static void chooseSource(SetupWizard wizard,Path source) throws Exception
    {
        javax.swing.SwingUtilities.invokeLater(() -> wizard.getRootPane().getDefaultButton().doClick());
        for(int attempt=0;attempt<400;attempt++)
        {
            boolean[] chosen={false};
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for(java.awt.Window window:java.awt.Window.getWindows())
                    for(java.awt.Component component:descendants(window))
                        if(component instanceof javax.swing.JFileChooser chooser && chooser.isShowing())
                        { chooser.setSelectedFile(source.toFile()); chooser.approveSelection(); chosen[0]=true; return; }
            });
            if(chosen[0]) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Source chooser did not open");
    }

    private static void exerciseCatalogFailure(SetupWizard wizard, Path root) throws Exception
    {
        Path catalog = root.resolve("database/managed-recordings.sqlite");
        byte[] before = Files.readAllBytes(catalog);
        javax.swing.SwingUtilities.invokeAndWait(() -> backupChoice(wizard).doClick());
        clickPrimary(wizard);
        await(wizard, SetupStep.SOURCE);
        capture(wizard, root, "upgrade-catalog-failed");
        click(wizard, "Continue without managed recordings");
        awaitAfterSource(wizard);
        if(!java.util.Arrays.equals(before, Files.readAllBytes(catalog)))
            throw new AssertionError("Failed catalog update changed the source");
        Files.setPosixFilePermissions(catalog, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        if(!hasVisibleText(wizard, "Managed Recordings unavailable"))
            throw new AssertionError("Continuation hid the unavailable recordings warning");
        var restart = SetupWizard.class.getDeclaredField("restartRequired"); restart.setAccessible(true);
        if(restart.getBoolean(wizard)) throw new AssertionError("Optional catalog failure blocked all receiving");
        capture(wizard, root, "upgrade-catalog-unavailable");
        click(wizard, "Exit setup");
        awaitClosed(wizard);
    }

    /** The successful listener check must leave the first-run guidance on screen until the final launch action. */
    private static void assertCertificateGuide(SetupWizard target,Path root) throws Exception
    {
        awaitVisibleText(target,"Setup complete");
        var finished=SetupWizard.class.getDeclaredField("finished"); finished.setAccessible(true);
        var progressField=SetupWizard.class.getDeclaredField("progress"); progressField.setAccessible(true);
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            try
            {
                if(!target.isShowing() || finished.getBoolean(target))
                    throw new AssertionError("Certificate guidance was dismissed before the user could read it");
                var progress=(SetupProgress)progressField.get(target);
                if(progress.get(SetupStep.REVIEW)==SetupProgress.State.COMPLETE || progress.isComplete())
                    throw new AssertionError("Review was marked complete before the final launch action");
                for(String expected:new String[]{"18091", "Browser warning example", "Advanced",
                    "Continue to 127.0.0.1 (unsafe)"})
                    if(!hasVisibleText(target,expected))
                        throw new AssertionError("Certificate guidance is missing: "+expected);
                if(descendants(target).stream().noneMatch(component -> component instanceof javax.swing.JButton button &&
                    button.isShowing() && button.isEnabled() && button.getText().equals("Launch VCE & open browser")))
                    throw new AssertionError("Certificate guidance has no final launch action");
            }
            catch(IllegalAccessException e) { throw new RuntimeException(e); }
        });
        var savedProgress=SetupProgress.read(root.resolve("database/sdrtrunk.sqlite"));
        if(savedProgress.get(SetupStep.REVIEW)==SetupProgress.State.COMPLETE || savedProgress.isComplete())
            throw new AssertionError("Review was saved as complete before the final launch action");
        javax.swing.SwingUtilities.invokeAndWait(() -> target.setSize(1080,800));
        assertLightWizard(target);
        capture(target,root,"11-certificate-guide");
        javax.swing.SwingUtilities.invokeAndWait(() -> target.setSize(800,600));
        capture(target,root,"12-certificate-guide-small");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            for(java.awt.Component component:descendants(target))
                if(component instanceof javax.swing.JScrollPane scroll &&
                    scroll.getViewport().getView() instanceof SetupPage)
                {
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                    return;
                }
            throw new AssertionError("Certificate guide scroll pane unavailable");
        });
        capture(target,root,"13-certificate-guide-small-scrolled");
    }

    /** Render result callbacks without making a RadioReference request or using real credentials. */
    private static void exerciseFeedback(SetupWizard target,Path root) throws Exception
    {
        var callback=SetupWizard.class.getDeclaredMethod("showRadioReferenceResult",javax.swing.JPanel.class,boolean.class);
        callback.setAccessible(true);
        javax.swing.JPanel[] status={null};
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            for(var component:descendants(target)) if(component instanceof javax.swing.JPanel panel &&
                "RadioReference connection status".equals(panel.getAccessibleContext().getAccessibleName())) status[0]=panel;
            if(status[0]==null) throw new AssertionError("Connection status unavailable");
            try { callback.invoke(target,status[0],true); } catch(Exception e) { throw new RuntimeException(e); }
            if(((WizardNotice)status[0].getComponent(0)).getClientProperty("wizard.noticeTone")!=WizardNotice.Tone.SUCCESS)
                throw new AssertionError("Premium access is not a success notice");
        });
        capture(target,root,"05b-premium-success");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            namedInput(target,"RadioReference username").setText("smoke-only");
            if(status[0].getComponent(0) instanceof WizardNotice) throw new AssertionError("Editing left a stale success notice");
            try { callback.invoke(target,status[0],false); } catch(Exception e) { throw new RuntimeException(e); }
            if(((WizardNotice)status[0].getComponent(0)).getClientProperty("wizard.noticeTone")!=WizardNotice.Tone.WARNING)
                throw new AssertionError("Unavailable premium access is not a warning notice");
        });
        capture(target,root,"05c-premium-unavailable");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            namedInput(target,"RadioReference username").setText("smoke-edited");
            try {
                var fail=SetupWizard.class.getDeclaredMethod("fail",String.class); fail.setAccessible(true);
                fail.invoke(target,"RadioReference connection failed. Check your account details and connection, then try again.");
                var field=SetupWizard.class.getDeclaredField("danger"); field.setAccessible(true);
                var danger=(javax.swing.JTextArea)field.get(target);
                if(!danger.isVisible() || !danger.isOpaque() || !danger.getBackground().equals(WizardNotice.background(WizardNotice.Tone.ERROR)))
                    throw new AssertionError("Failure is not a persistent red notice");
                if(descendants(target).stream().noneMatch(component -> component instanceof javax.swing.JButton button &&
                    button.isShowing() && button.getText().equals("Copy error")))
                    throw new AssertionError("Failure does not offer a copyable error message");
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        capture(target,root,"05d-connection-failure");
    }

    /** Source intent is independent of file format and creates no profile during selection. */
    private static void assertSourceChoices(SetupWizard target) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var choices=descendants(target).stream().filter(c -> c instanceof javax.swing.JRadioButton r && r.isShowing())
                .map(javax.swing.JRadioButton.class::cast).toList();
            if(choices.size()!=3) throw new AssertionError("New setup must offer exactly three source intents");
            select(target,"Upgrade existing VCE");
            if(!target.getRootPane().getDefaultButton().getText().equals("Choose settings"))
                throw new AssertionError("VCE upgrade must first choose and review its source");
            select(target,"Import XML playlist");
            if(!target.getRootPane().getDefaultButton().getText().equals("Choose settings"))
                throw new AssertionError("XML import must first choose and review its playlist");
            select(target,"Start fresh");
            if(descendants(target).stream().anyMatch(c -> c instanceof javax.swing.JTextField && c.isShowing()))
                throw new AssertionError("Start fresh shows irrelevant source fields");
        });
    }

    private static void assertAndTypePort(SetupWizard target) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JSpinner &&
                component.isShowing())) throw new AssertionError("Web port must start inside collapsed More options");
            var moreOptions=descendants(target).stream().filter(component -> component instanceof javax.swing.JButton button &&
                button.isShowing() && "More options".equals(button.getAccessibleContext().getAccessibleName()))
                .map(javax.swing.JButton.class::cast).findFirst().orElseThrow();
            moreOptions.doClick();
            target.validate();
            for(java.awt.Component component:descendants(target))
            {
                if(component instanceof javax.swing.JSpinner spinner && spinner.isShowing())
                {
                    var editor=(javax.swing.JSpinner.DefaultEditor)spinner.getEditor();
                    if(!editor.getTextField().getText().equals("8090"))
                        throw new AssertionError("The port is not displayed as ungrouped 8090: "+editor.getTextField().getText());
                    //Do not call setValue/commitEdit here: the page must save what the user actually typed.
                    editor.getTextField().setText("18091");
                    return;
                }
            }
            throw new AssertionError("Web port field unavailable");
        });
    }

    /** Exercises installation/UI validation with a local codec fixture; never downloads or starts a receiver. */
    private static void exerciseJmbe(SetupWizard target,Path root) throws Exception
    {
        Path inputs=Files.createDirectories(root.resolve("smoke-inputs"));
        Path library=io.github.dsheirer.jmbe.JmbeLibraryFixture.create(inputs,"1.0.14");
        Path invalid=inputs.resolve("not-a-library.jar");
        Files.writeString(invalid,"invalid smoke fixture, not executable");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            select(target,"Set up digital voice");
            javax.swing.JButton primary=target.getRootPane().getDefaultButton();
            if(primary.isEnabled()) throw new AssertionError("Digital voice setup can start without consent");
            javax.swing.JCheckBox consent=null;
            for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JCheckBox checkbox &&
                checkbox.isShowing() && checkbox.getText().equals("I agree to download and build JMBE on this computer.")) consent=checkbox;
            if(consent==null) throw new AssertionError("Download/build consent unavailable");
            consent.setSelected(true);
            if(!primary.isEnabled()) throw new AssertionError("Consent did not enable the digital voice primary action");
            consent.setSelected(false);
            if(primary.isEnabled()) throw new AssertionError("Removing consent left the primary action enabled");
            select(target,"Use a JMBE file I already have");
            namedInput(target,"JMBE library file").setText(invalid.toString());
        });
        click(target,"Continue"); await(target,SetupStep.JMBE);
        assertFailure(target,root);
        capture(target,root,"04a-jmbe-invalid-file");
        javax.swing.SwingUtilities.invokeAndWait(()-> namedInput(target,"JMBE library file").setText(library.toString()));
        click(target,"Continue"); await(target,SetupStep.JMBE);
        assertJmbeSuccess(target,root);
        capture(target,root,"04b-jmbe-success");
        Path installed=preferences(target).getJmbeLibraryPreference().getPathJmbeLibrary();
        byte[] working=Files.readAllBytes(installed);
        click(target,"Change digital voice setup…");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            select(target,"Use a JMBE file I already have");
            namedInput(target,"JMBE library file").setText(invalid.toString());
        });
        click(target,"Continue"); await(target,SetupStep.JMBE);
        assertFailure(target,root);
        if(!installed.equals(preferences(target).getJmbeLibraryPreference().getPathJmbeLibrary()) ||
            !java.util.Arrays.equals(working,Files.readAllBytes(installed)))
            throw new AssertionError("Failed replacement changed the working JMBE library");
        capture(target,root,"04c-jmbe-replacement-failed");
        click(target,"Keep current setup"); await(target,SetupStep.JMBE);
        assertJmbeSuccess(target,root);
        click(target,"Continue");
    }

    private static void assertJmbeSuccess(SetupWizard target,Path root) throws Exception
    {
        if(SetupProgress.read(root.resolve("database/sdrtrunk.sqlite")).get(SetupStep.JMBE)!=SetupProgress.State.COMPLETE)
            throw new AssertionError("Validated JMBE installation was not completed");
        assertCollapsedDetails(target);
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            if(!hasVisibleText(target,"Digital voice is ready")) throw new AssertionError("Missing persistent digital voice success message");
            var primary=target.getRootPane().getDefaultButton();
            if(!primary.isEnabled() || !primary.getText().equals("Continue"))
                throw new AssertionError("Successful digital voice setup did not change its primary action to Continue");
            if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JCheckBox checkbox &&
                checkbox.isShowing() && checkbox.getText().startsWith("I agree to download")))
                throw new AssertionError("Successful digital voice setup still shows download consent");
        });
    }

    private static void assertFailure(SetupWizard target,Path root) throws Exception
    {
        if(SetupProgress.read(root.resolve("database/sdrtrunk.sqlite")).get(SetupStep.JMBE)!=SetupProgress.State.NEEDS_ATTENTION)
            throw new AssertionError("Failed JMBE installation was given a success checkmark");
        assertCollapsedDetails(target);
        var banner=SetupWizard.class.getDeclaredField("danger"); banner.setAccessible(true);
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            try { if(!((javax.swing.JTextArea)banner.get(target)).isShowing()) throw new AssertionError("Failure has no persistent warning"); }
            catch(IllegalAccessException e) { throw new RuntimeException(e); }
        });
    }

    private static void assertCollapsedDetails(SetupWizard target) throws Exception
    {
        var details=SetupWizard.class.getDeclaredField("diagnostics"); details.setAccessible(true);
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            try { if(((javax.swing.JScrollPane)details.get(target)).isShowing()) throw new AssertionError("Technical job details should be collapsed by default"); }
            catch(IllegalAccessException e) { throw new RuntimeException(e); }
        });
    }

    private static io.github.dsheirer.preference.UserPreferences preferences(SetupWizard target) throws Exception
    {
        var field=SetupWizard.class.getDeclaredField("preferences"); field.setAccessible(true);
        return (io.github.dsheirer.preference.UserPreferences)field.get(target);
    }

    /** Swing helpers below are called on the event-dispatch thread. */
    private static void select(SetupWizard target,String prefix)
    {
        for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JRadioButton radio &&
            radio.isShowing() && radio.getAccessibleContext().getAccessibleName().startsWith(prefix))
        {
            radio.setSelected(true);
            target.validate();
            return;
        }
        throw new AssertionError("Choice unavailable: "+prefix);
    }

    private static javax.swing.JTextField visibleInput(SetupWizard target)
    {
        var inputs=descendants(target).stream().filter(component -> component instanceof javax.swing.JTextField &&
            !(component instanceof javax.swing.JPasswordField) && component.isShowing()).toList();
        if(inputs.size()!=1) throw new AssertionError("Expected one selected source field, found "+inputs.size());
        return (javax.swing.JTextField)inputs.getFirst();
    }

    private static javax.swing.JTextField namedInput(SetupWizard target,String name)
    {
        for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JTextField field &&
            field.isShowing() && name.equals(field.getAccessibleContext().getAccessibleName())) return field;
        throw new AssertionError("Input unavailable: "+name);
    }

    private static boolean hasVisibleText(SetupWizard target,String value)
    {
        for(java.awt.Component component:descendants(target))
        {
            if(!component.isShowing()) continue;
            if(component instanceof javax.swing.JLabel label && label.getText()!=null && label.getText().contains(value)) return true;
            if(component instanceof javax.swing.text.JTextComponent text && text.getText().contains(value)) return true;
        }
        return false;
    }

    private static void await(SetupWizard target,SetupStep expected) throws Exception
    {
        var step=SetupWizard.class.getDeclaredField("step"); step.setAccessible(true);
        var busy=SetupWizard.class.getDeclaredField("busy"); busy.setAccessible(true);
        for(int i=0;i<600;i++)
        {
            boolean[] ready={false};
            javax.swing.SwingUtilities.invokeAndWait(()-> { try { ready[0]=step.get(target)==expected && !busy.getBoolean(target); } catch(Exception e) { throw new RuntimeException(e); } });
            if(ready[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out at "+expected);
    }

    private static void awaitVisibleText(SetupWizard target,String expected) throws Exception
    {
        for(int i=0;i<600;i++)
        {
            boolean[] visible={false};
            javax.swing.SwingUtilities.invokeAndWait(()-> visible[0]=target.isShowing() && hasVisibleText(target,expected));
            if(visible[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for wizard text: "+expected);
    }

    private static void awaitClosed(SetupWizard target) throws Exception
    {
        for(int i=0;i<300;i++)
        {
            boolean[] closed={false};
            javax.swing.SwingUtilities.invokeAndWait(()-> closed[0]=!target.isShowing());
            if(closed[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Final launch action did not close the wizard");
    }
    private static void click(SetupWizard target,String title) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            if(title.equals("Continue"))
            {
                var primary=target.getRootPane().getDefaultButton();
                if(primary==null || !primary.isEnabled() || !primary.isShowing()) throw new AssertionError("Primary action unavailable");
                primary.doClick();
                return;
            }
            for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JButton button &&
                (button.getText().equals(title) || title.equals("Finish setup") && button.getText().equals("Retry launch")) &&
                button.isEnabled() && button.isShowing()) { button.doClick(); return; }
            throw new AssertionError("Button unavailable: "+title);
        });
    }
    private static java.util.List<java.awt.Component> descendants(java.awt.Container parent)
    {
        var result=new java.util.ArrayList<java.awt.Component>();
        for(java.awt.Component component:parent.getComponents()) { result.add(component); if(component instanceof java.awt.Container child) result.addAll(descendants(child)); }
        return result;
    }
    private static void capture(SetupWizard target,Path root,String name) throws Exception
    {
        //Let resize/theme events and wrapping revalidation settle before taking the rendered snapshot.
        javax.swing.SwingUtilities.invokeAndWait(target::validate);
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            target.validate();
            java.awt.Container content=target.getContentPane();
            var image=new java.awt.image.BufferedImage(content.getWidth(),content.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics(); content.paintAll(graphics); graphics.dispose();
            try
            {
                String destination=System.getProperty("wizard.screenshots","");
                Path screenshots=destination.isBlank() ? root.resolveSibling(root.getFileName()+"-screenshots") :
                    Path.of(destination).resolve(System.getProperty("wizard.exercise","manual"));
                Files.createDirectories(screenshots);
                javax.imageio.ImageIO.write(image,"png",screenshots.resolve(name+".png").toFile());
            }
            catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
