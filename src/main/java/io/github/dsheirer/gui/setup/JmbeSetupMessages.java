package io.github.dsheirer.gui.setup;

/** Only known build stages become progress headlines; compiler output stays in bounded details. */
final class JmbeSetupMessages
{
    private JmbeSetupMessages() {}
    static String stage(String message)
    {
        if(message==null) return null;
        return switch(message)
        {
            case "Downloading JMBE creator..." -> "Downloading digital voice tools…";
            case "Extracting creator..." -> "Preparing the download…";
            case "Building JMBE..." -> "Building your digital voice library. This can take a few minutes…";
            case "Validating library..." -> "Checking the new voice library…";
            case "Installing verified library..." -> "Installing digital voice support…";
            case "Cleaning temporary build files..." -> "Finishing digital voice setup…";
            default -> null;
        };
    }
}
