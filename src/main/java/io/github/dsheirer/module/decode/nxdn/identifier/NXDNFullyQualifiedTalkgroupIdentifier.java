package io.github.dsheirer.module.decode.nxdn.identifier;

import io.github.dsheirer.identifier.Role;
import java.util.Objects;

/**
 * Inter-system or roaming NXDN talkgroup identifier.
 */
public class NXDNFullyQualifiedTalkgroupIdentifier extends NXDNTalkgroupIdentifier
{
    private final int mSystem;

    /**
     * Constructs an instance
     *
     * @param system that is home for the talkgroup
     * @param value for the talkgroup
     * @param role  for the talkgroup
     */
    public NXDNFullyQualifiedTalkgroupIdentifier(int system, int value, Role role)
    {
        super(value, role);
        mSystem = system;
    }

    /**
     * Home NXDN system for this talkgroup.
     */
    public int getSystem()
    {
        return mSystem;
    }

    @Override
    public String toString()
    {
        return mSystem + "." + super.toString();
    }

    @Override
    public boolean equals(Object object)
    {
        return this == object || object instanceof NXDNFullyQualifiedTalkgroupIdentifier identifier &&
            getSystem() == identifier.getSystem() && super.equals(identifier);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(super.hashCode(), getSystem());
    }

    /**
     * Creates an instance with a role of TO
     * @param system that is home for the talkgroup
     * @param talkgroup value
     * @return TO instance
     */
    public static NXDNFullyQualifiedTalkgroupIdentifier createTo(int system, int talkgroup)
    {
        return new NXDNFullyQualifiedTalkgroupIdentifier(system, talkgroup, Role.TO);
    }

    /**
     * Creates an instance with a role of ANY
     * @param system that is home for the talkgroup
     * @param talkgroup value
     * @return ANY instance
     */
    public static NXDNFullyQualifiedTalkgroupIdentifier createAny(int system, int talkgroup)
    {
        return new NXDNFullyQualifiedTalkgroupIdentifier(system, talkgroup, Role.ANY);
    }
}
