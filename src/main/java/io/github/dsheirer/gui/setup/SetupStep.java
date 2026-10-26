package io.github.dsheirer.gui.setup;

/** Stable, ordered setup identifiers; persisted names must not be renamed. */
public enum SetupStep
{
    SOURCE("Your settings"), ADMINISTRATOR("Administrator"), WEB("Web access"),
    JMBE("Digital voice"), RADIO_REFERENCE("RadioReference"), ACTIVITY("Statistics & history"),
    RECORDINGS("Recordings"),
    HARDWARE("Your radios"), CALIBRATION("Improve performance"), REVIEW("Review & finish");

    private final String title;
    SetupStep(String title) { this.title = title; }
    public String title() { return title; }
}
