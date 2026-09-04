package io.github.dsheirer.gui.setup;

/** Stable, ordered setup identifiers; persisted names must not be renamed. */
public enum SetupStep
{
    SOURCE("Starting point"), ADMINISTRATOR("Administrator"), WEB("Web access"),
    JMBE("Digital audio"), RADIO_REFERENCE("RadioReference"), ACTIVITY("Statistics & history"),
    HARDWARE("Your radios"), CALIBRATION("Optimize decoding"), REVIEW("Review & finish");

    private final String title;
    SetupStep(String title) { this.title = title; }
    public String title() { return title; }
}
