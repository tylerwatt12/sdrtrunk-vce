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
        if(mode.equals("revisit"))
        {
            await(target,SetupStep.CALIBRATION);
            capture(target,root,"12-deferred-benchmark-next-launch");
            click(target,"Skip this time"); await(target,SetupStep.REVIEW);
            click(target,"Finish & launch");
            return;
        }
        capture(target,root,"01-source");
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
        await(target,SetupStep.HARDWARE);
        capture(target,root,"07-hardware"); click(target,"Skip"); await(target,SetupStep.CALIBRATION);
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
        click(target,"Finish & launch");
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
