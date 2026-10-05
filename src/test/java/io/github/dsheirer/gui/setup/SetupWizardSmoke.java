package io.github.dsheirer.gui.setup;

import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Manual graphical smoke harness. Finishing NEVER constructs a receiver or starts channels. */
public final class SetupWizardSmoke
{
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
                io.github.dsheirer.database.upgrade.Format31TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));
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
                Files.writeString(root.resolve("database/backups"), "unrelated file");
            }
            else io.github.dsheirer.database.upgrade.Format30TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));
            if(mode.equals("upgrade-retry")) Files.writeString(root.resolve("database/backups"), "unrelated file");
        }
        if(!System.getProperty("wizard.exercise", "").isBlank())
        {
            Thread driver=new Thread(()-> {
                try { exercise(root); }
                catch(Throwable e) { e.printStackTrace(); System.exit(2); }
            },"wizard smoke driver");
            driver.setDaemon(true); driver.start();
        }
        try
        {
            var result=SetupWizard.run(System.getProperty("wizard.exercise", "").equals("revisit") ?
                new String[0] : new String[]{"--setup-wizard"},root,null);
            if(result!=null) result.lock().close();
        }
        finally { SqlitePreferencesFactory.shutdown(); System.exit(0); }
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
        if(mode.startsWith("upgrade-"))
        {
            if(mode.equals("upgrade-catalog-failure")) exerciseCatalogFailure(target, root);
            else exerciseUpgrade(target, root, mode);
            return;
        }
        if(mode.equals("revisit"))
        {
            await(target,SetupStep.CALIBRATION);
            capture(target,root,"12-deferred-benchmark-next-launch");
            click(target,"Skip this time"); await(target,SetupStep.REVIEW);
            click(target,"Finish & launch");
            awaitClosed(target);
            return;
        }
        capture(target,root,"01-source");
        if(mode.equals("theme") || mode.equals("import-theme"))
        {
            click(target,"Dark mode");
            if(Files.exists(root.resolve("database/sdrtrunk.sqlite"))) throw new AssertionError("Theme preview created a database before source selection");
            capture(target,root,"01b-dark-source");
        }
        if(!mode.equals("resume")) assertSourceChoices(target);
        if(mode.startsWith("import"))
        {
            String source=System.getProperty("wizard.source");
            javax.swing.SwingUtilities.invokeAndWait(()-> {
                select(target,mode.equals("import-file") ? "Import a SQLite" : "Copy a previous");
                visibleInput(target).setText(source);
            });
            click(target,"Continue"); await(target,SetupStep.SOURCE);
            click(target,"Confirm import"); await(target,SetupStep.SOURCE);
            if(mode.equals("import-theme") && preferences(target).getApplicationPreference().getTheme()!=io.github.dsheirer.gui.theme.Theme.DARK)
                throw new AssertionError("Explicit theme choice was not retained after importing");
            var imported=SetupProgress.read(root.resolve("database/sdrtrunk.sqlite"));
            if(!imported.isImported() || imported.get(SetupStep.ADMINISTRATOR)!=SetupProgress.State.CARRIED_OVER || imported.get(SetupStep.WEB)!=SetupProgress.State.CARRIED_OVER || imported.get(SetupStep.ACTIVITY)!=SetupProgress.State.CARRIED_OVER)
                throw new AssertionError("Imported valid settings were not carried over");
            click(target,"Continue"); await(target,SetupStep.JMBE);
        }
        else
        {
            if(!mode.equals("resume")) { click(target,"Continue"); await(target,SetupStep.SOURCE); }
            click(target,"Continue"); await(target,SetupStep.ADMINISTRATOR);
            capture(target,root,"02-admin");
            javax.swing.SwingUtilities.invokeAndWait(()-> {
                for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JPasswordField field && field.isShowing()) field.setText("smoke-only-password");
            });
            if(mode.equals("interrupt")) { click(target,"Exit setup"); return; }
            click(target,"Continue"); await(target,SetupStep.WEB);
            assertAndTypePort(target);
            if(mode.equals("theme"))
            {
                if(preferences(target).getApplicationPreference().getTheme()!=io.github.dsheirer.gui.theme.Theme.DARK)
                    throw new AssertionError("Pre-database theme choice was not saved");
                click(target,"Light mode");
                if(preferences(target).getApplicationPreference().getTheme()!=io.github.dsheirer.gui.theme.Theme.LIGHT)
                    throw new AssertionError("Theme choice was not updated");
            }
            capture(target,root,"03-web");
            if(mode.equals("feedback"))
            {
                click(target,"▸  About secure access");
                capture(target,root,"03b-web-expanded");
                click(target,"▾  About secure access");
            }
            click(target,"Continue"); await(target,SetupStep.JMBE);
            if(preferences(target).getApplicationPreference().getStatsWebServerPort()!=18091)
                throw new AssertionError("The typed port was not committed");
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
        capture(target,root,"08-benchmark"); click(target,"Skip this time"); await(target,SetupStep.REVIEW);
        capture(target,root,"09-review");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            try
            {
                var field=SetupWizard.class.getDeclaredField("preferences"); field.setAccessible(true);
                var preferences=(io.github.dsheirer.preference.UserPreferences)field.get(target);
                preferences.getApplicationPreference().setTheme(io.github.dsheirer.gui.theme.Theme.DARK);
                target.setSize(800,600);
            }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        capture(target,root,"10-review-dark-small");
        if(mode.equals("bind-failure"))
        {
            try(var occupied=new java.net.ServerSocket(18091,1,java.net.InetAddress.getLoopbackAddress()))
            {
                click(target,"Finish & launch"); await(target,SetupStep.REVIEW);
                var banner=SetupWizard.class.getDeclaredField("danger"); banner.setAccessible(true);
                if(!((javax.swing.JTextArea)banner.get(target)).isVisible()) throw new AssertionError("Bind failure did not remain in the wizard");
                capture(target,root,"11-bind-error");
            }
        }
        var web=preferences(target).getApplicationPreference();
        boolean guideExpected=web.isStatsWebServerHttpsEnabled() &&
            web.getStatsWebServerCertificateMode()==io.github.dsheirer.preference.application.WebCertificateMode.AUTOMATIC;
        click(target,"Finish & launch");
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
        javax.swing.JCheckBox[] checkbox = new javax.swing.JCheckBox[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            checkbox[0] = descendants(wizard).stream()
                .filter(component -> component instanceof javax.swing.JCheckBox box && box.isShowing() &&
                    box.getText().equals("Create a recovery backup first"))
                .map(component -> (javax.swing.JCheckBox)component).findFirst().orElseThrow();
            if(!checkbox[0].isSelected()) throw new AssertionError("Upgrade backup is not on by default");
            if(descendants(wizard).stream().anyMatch(component -> component instanceof javax.swing.JButton button &&
                button.isShowing() && (button.getText().contains("Check safely") || button.getText().contains("Skip safety"))))
                throw new AssertionError("Upgrade still requires the discarded safety-review workflow");
        });
        capture(wizard, root, "upgrade-ready");
        openUpgradeConfirmation(wizard);
        answerUpgradeConfirmation("Cancel");
        await(wizard, SetupStep.SOURCE);
        if(!java.util.Arrays.equals(source, Files.readAllBytes(database)))
            throw new AssertionError("Cancelling confirmation changed the database");
        if(mode.equals("upgrade-no-backup"))
            javax.swing.SwingUtilities.invokeAndWait(() -> checkbox[0].doClick());
        openUpgradeConfirmation(wizard);
        answerUpgradeConfirmation("Update");
        await(wizard, SetupStep.SOURCE);
        if(mode.equals("upgrade-retry"))
        {
            var danger = SetupWizard.class.getDeclaredField("danger"); danger.setAccessible(true);
            if(!((javax.swing.JTextArea)danger.get(wizard)).isVisible())
                throw new AssertionError("Failed backup did not show an inline error");
            if(!java.util.Arrays.equals(source, Files.readAllBytes(database)))
                throw new AssertionError("Failed backup changed the database");
            javax.swing.SwingUtilities.invokeAndWait(() -> checkbox[0].doClick());
            openUpgradeConfirmation(wizard);
            answerUpgradeConfirmation("Update");
            await(wizard, SetupStep.SOURCE);
            if(!Files.readString(root.resolve("database/backups")).equals("unrelated file"))
                throw new AssertionError("Retry replaced unrelated backup-path content");
        }
        try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            if(io.github.dsheirer.database.upgrade.DatabaseFormatCatalog.requireCurrent(connection).version() != 31)
                throw new AssertionError("Update did not reach the current format");
        }
        if(mode.equals("upgrade-backup"))
        {
            try(var files = Files.list(root.resolve("database/backups")))
            {
                if(files.filter(Files::isRegularFile).count() != 1)
                    throw new AssertionError("Update did not retain exactly one backup");
            }
        }
        else if(mode.equals("upgrade-no-backup") && Files.exists(root.resolve("database/backups")))
            throw new AssertionError("Backup-off update created backup files");
        capture(wizard, root, "upgrade-complete");
        if(mode.equals("upgrade-backup"))
        {
            //Exercise automatic continuation without entering hardware discovery or launching a receiver.
            var field = SetupWizard.class.getDeclaredField("progress"); field.setAccessible(true);
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try { ((SetupProgress)field.get(wizard)).set(SetupStep.ADMINISTRATOR, SetupProgress.State.PENDING); }
                catch(IllegalAccessException e) { throw new RuntimeException(e); }
            });
            await(wizard, SetupStep.ADMINISTRATOR);
            click(wizard, "Back");
            await(wizard, SetupStep.SOURCE);
            if(descendants(wizard).stream().anyMatch(component -> component instanceof javax.swing.JCheckBox box &&
                box.isShowing() && box.getText().equals("Create a recovery backup first")))
                throw new AssertionError("Back offered to run the committed update again");
        }
        click(wizard, "Exit setup");
        awaitClosed(wizard);
    }

    private static void answerUpgradeConfirmation(String answer) throws Exception
    {
        for(int attempt = 0; attempt < 100; attempt++)
        {
            boolean[] answered = {false};
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for(java.awt.Window window: java.awt.Window.getWindows())
                    if(window instanceof javax.swing.JDialog dialog && dialog.isShowing() &&
                        dialog.getTitle().equals("Update saved data?"))
                        for(java.awt.Component component: descendants(dialog))
                            if(component instanceof javax.swing.JButton button && button.isShowing() && button.getText().equals(answer))
                            { button.doClick(); answered[0] = true; }
            });
            if(answered[0]) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Upgrade confirmation not shown");
    }

    private static void openUpgradeConfirmation(SetupWizard wizard)
    {
        //The modal confirmation runs a nested Swing event loop; do not block the driver waiting for it to close.
        javax.swing.SwingUtilities.invokeLater(() -> wizard.getRootPane().getDefaultButton().doClick());
    }

    private static void exerciseCatalogFailure(SetupWizard wizard, Path root) throws Exception
    {
        Path catalog = root.resolve("database/managed-recordings.sqlite");
        byte[] before = Files.readAllBytes(catalog);
        openUpgradeConfirmation(wizard);
        answerUpgradeConfirmation("Update");
        await(wizard, SetupStep.SOURCE);
        capture(wizard, root, "upgrade-catalog-failed");
        click(wizard, "Continue without managed recordings");
        await(wizard, SetupStep.SOURCE);
        if(!java.util.Arrays.equals(before, Files.readAllBytes(catalog)))
            throw new AssertionError("Failed catalog update changed the source");
        if(!Files.readString(root.resolve("database/backups")).equals("unrelated file"))
            throw new AssertionError("Failed catalog update replaced unrelated backup-path content");
        if(!hasVisibleText(wizard, "Managed recordings are unavailable"))
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
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            try
            {
                preferences(target).getApplicationPreference().setTheme(io.github.dsheirer.gui.theme.Theme.LIGHT);
                target.setSize(1080,800);
            }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        capture(target,root,"11-certificate-guide");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            try
            {
                preferences(target).getApplicationPreference().setTheme(io.github.dsheirer.gui.theme.Theme.DARK);
                target.setSize(800,600);
            }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        capture(target,root,"12-certificate-guide-dark-small");
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
        capture(target,root,"13-certificate-guide-dark-small-scrolled");
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

    /** No file selectors belong to Start fresh, and each import choice retains only its own source path. */
    private static void assertSourceChoices(SetupWizard target) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            select(target,"Start fresh");
            if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JTextField && component.isShowing()))
                throw new AssertionError("Start fresh shows an irrelevant source field");
            if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JButton button &&
                button.isShowing() && button.getText().startsWith("Browse")))
                throw new AssertionError("Start fresh shows an irrelevant Browse button");
            for(String choice:new String[]{"Copy a previous", "Import a SQLite", "Import legacy XML"})
            {
                select(target,choice);
                if(choice.equals("Import legacy XML") && !target.getRootPane().getDefaultButton().getText().equals("Import XML"))
                    throw new AssertionError("XML action promises a review instead of an import");
                javax.swing.JTextField input=visibleInput(target);
                if(!input.getText().isBlank()) throw new AssertionError("An unselected import source leaked into "+choice);
                input.setText(choice+"-smoke-source");
            }
            select(target,"Copy a previous");
            if(!visibleInput(target).getText().equals("Copy a previous-smoke-source"))
                throw new AssertionError("The folder source was replaced by another import option");
            select(target,"Start fresh");
            if(descendants(target).stream().anyMatch(component -> component instanceof javax.swing.JTextField && component.isShowing()))
                throw new AssertionError("Source controls remained visible after returning to Start fresh");
        });
    }

    private static void assertAndTypePort(SetupWizard target) throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(()-> {
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

    /** Exercises installation/UI validation with a metadata fixture; never downloads or executes a compiler. */
    private static void exerciseJmbe(SetupWizard target,Path root) throws Exception
    {
        Path inputs=Files.createDirectories(root.resolve("smoke-inputs"));
        Path library=inputs.resolve("jmbe-smoke.jar");
        Path invalid=inputs.resolve("not-a-library.jar");
        java.util.jar.Manifest manifest=new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Version","1.0.14");
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(library),manifest))
        {
            jar.putNextEntry(new java.util.jar.JarEntry("jmbe/JMBEAudioLibrary.class"));
            jar.write(0); jar.closeEntry();
        }
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
                (button.getText().equals(title) || title.equals("Finish & launch") && button.getText().equals("Retry launch")) &&
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
            var image=new java.awt.image.BufferedImage(target.getWidth(),target.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics(); target.paintAll(graphics); graphics.dispose();
            try
            {
                Path screenshots=root.resolveSibling(root.getFileName()+"-screenshots"); Files.createDirectories(screenshots);
                javax.imageio.ImageIO.write(image,"png",screenshots.resolve(name+".png").toFile());
            }
            catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
