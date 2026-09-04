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
        if(mode.startsWith("import"))
        {
            String source=System.getProperty("wizard.source");
            javax.swing.SwingUtilities.invokeAndWait(()-> {
                for(java.awt.Component component:descendants(target))
                {
                    if(component instanceof javax.swing.JRadioButton radio && radio.getAccessibleContext().getAccessibleName().startsWith(mode.equals("import-file") ? "Import a SQLite" : "Copy a previous")) radio.setSelected(true);
                    if(component instanceof javax.swing.JTextField field && !(component instanceof javax.swing.JPasswordField)) field.setText(source);
                }
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
            for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JPasswordField field) field.setText("smoke-only-password");
        });
        if(mode.equals("interrupt")) { click(target,"Exit setup"); return; }
        click(target,"Continue"); await(target,SetupStep.WEB);
        capture(target,root,"03-web");
        javax.swing.SwingUtilities.invokeAndWait(()-> {
            for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JSpinner spinner) spinner.setValue(18091);
        });
        click(target,"Continue"); await(target,SetupStep.JMBE);
        }
        capture(target,root,"04-jmbe"); click(target,"Set up later"); await(target,SetupStep.RADIO_REFERENCE);
        capture(target,root,"05-radioreference"); click(target,"Set up later");
        if(!mode.startsWith("import"))
        {
            await(target,SetupStep.ACTIVITY); capture(target,root,"06-activity"); click(target,"Continue");
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
            for(java.awt.Component component:descendants(target)) if(component instanceof javax.swing.JButton button &&
                (button.getText().equals(title) || title.equals("Continue") && button.getText().startsWith("Continue in ") ||
                    title.equals("Finish & launch") && button.getText().equals("Retry launch")) && button.isEnabled()) { button.doClick(); return; }
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
        javax.swing.SwingUtilities.invokeAndWait(()-> {
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
