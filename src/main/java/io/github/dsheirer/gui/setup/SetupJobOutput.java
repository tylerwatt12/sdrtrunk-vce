package io.github.dsheirer.gui.setup;

import java.util.function.Consumer;

/** Bounded worker output. UI timers poll instead of queueing a callback for each line. */
public final class SetupJobOutput implements Consumer<String>
{
    private final StringBuilder text = new StringBuilder();
    public synchronized void accept(String message)
    {
        if(message == null) return;
        text.append(message, 0, Math.min(message.length(), 2048)).append('\n');
        if(text.length() > 65536) text.delete(0, text.length() - 65536);
    }
    public synchronized String snapshot() { return text.toString(); }
}
