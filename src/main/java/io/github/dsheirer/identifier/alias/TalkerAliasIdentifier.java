/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.identifier.alias;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierObservation;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.Objects;

/**
 * A decoded OTA name. Message assemblers initially produce unowned text; an accepting decoder or radio cache
 * must bind it to its actual source before publishing it. Binding is immutable and cannot transfer a name to a
 * different radio. Consumers use {@link #find(IdentifierCollection)} rather than reading loose alias text.
 */
public abstract class TalkerAliasIdentifier extends StringIdentifier
{
    private final RadioIdentifier mOwner;
    private final int mServingWacn;
    private final int mServingSystem;
    private final String mConfigurationId;
    private final String mNativeSystemKey;
    /**
     * Constructs an instance.
     * @param value of the string/alias identifier
     */
    protected TalkerAliasIdentifier(String value)
    {
        this(value, null, -1, -1, null, null);
    }

    private TalkerAliasIdentifier(String value, RadioIdentifier owner, int servingWacn, int servingSystem,
                                  String configurationId, String nativeSystemKey)
    {
        super(value, IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
        mOwner = owner;
        mServingWacn = servingWacn;
        mServingSystem = servingSystem;
        mConfigurationId = configurationId;
        mNativeSystemKey = nativeSystemKey;
    }

    @Override
    public boolean isValid()
    {
        return getValue() != null && !getValue().isBlank();
    }

    public final boolean isBound()
    {
        return mOwner != null;
    }

    int servingWacn()
    {
        return mServingWacn;
    }

    int servingSystem()
    {
        return mServingSystem;
    }

    String nativeSystemKey()
    {
        return mNativeSystemKey;
    }

    /** Called only after a producer has established the radio accompanying the completed name. */
    public final TalkerAliasIdentifier bindTo(RadioIdentifier radio)
    {
        return bindTo(radio, -1, -1, null, null);
    }

    public final TalkerAliasIdentifier bindTo(RadioIdentifier radio, String servingSystemKey)
    {
        return bindTo(radio, servingSystemKey, null);
    }

    public final TalkerAliasIdentifier bindTo(RadioIdentifier radio, String servingSystemKey, String configurationId)
    {
        //A channel fallback identifies configuration ownership, not a learned over-the-air system.
        if(RadioSystemKey.isChannelScoped(servingSystemKey)) servingSystemKey = null;
        boolean p25 = RadioSystemKey.isP25Native(servingSystemKey);
        return bindTo(radio, p25 ? Integer.parseInt(servingSystemKey, 4, 9, 16) : -1,
            p25 ? Integer.parseInt(servingSystemKey, 10, 13, 16) : -1, configurationId,
            p25 ? null : servingSystemKey);
    }

    /** Primitive serving fields avoid key formatting on decoder callbacks. */
    public final TalkerAliasIdentifier bindTo(RadioIdentifier radio, int servingWacn, int servingSystem)
    {
        return bindTo(radio, servingWacn, servingSystem, null, null);
    }

    public final TalkerAliasIdentifier bindTo(IdentifierCollection context)
    {
        Identifier source = source(context);
        return source instanceof RadioIdentifier radio ? bindTo(radio,
            networkValue(context, Form.WACN), networkValue(context, Form.SYSTEM), configurationId(context), null) : null;
    }

    private TalkerAliasIdentifier bindTo(RadioIdentifier radio, int servingWacn, int servingSystem,
                                        String configurationId, String nativeSystemKey)
    {
        if(!isValid() || !usable(radio) || protocol(getProtocol()) != protocol(radio.getProtocol()) ||
            mOwner != null && (!sameRadio(mOwner, radio, servingWacn >= 0 ? servingWacn : mServingWacn,
                servingSystem >= 0 ? servingSystem : mServingSystem) ||
                scopeConflict(servingWacn, servingSystem, configurationId, radio, nativeSystemKey)))
        {
            return null;
        }

        //Keep a proven canonical owner when a later abbreviated observation supplies only its working address.
        RadioIdentifier owner = mOwner instanceof FullyQualifiedRadioIdentifier &&
            !(radio instanceof FullyQualifiedRadioIdentifier) ? mOwner : radio;
        int wacn = servingWacn >= 0 ? servingWacn : mServingWacn;
        int system = servingSystem >= 0 ? servingSystem : mServingSystem;
        String configuration = configurationId != null ? configurationId : mConfigurationId;
        String nativeScope = nativeSystemKey != null ? nativeSystemKey : mNativeSystemKey;
        if(IdentifierObservation.same(owner, mOwner) && wacn == mServingWacn && system == mServingSystem &&
            Objects.equals(configuration, mConfigurationId) && Objects.equals(nativeScope, mNativeSystemKey))
        {
            return this;
        }
        //NXDN's protocol identifier exposes a legacy Type-D setter. Capture that domain, never its mutable flag.
        if(owner instanceof NXDNRadioIdentifier nxdn)
        {
            owner = nxdn.isTypeD() ? NXDNRadioIdentifier.createTypeDFrom(nxdn.getValue()) :
                NXDNRadioIdentifier.createFrom(nxdn.getValue());
        }
        return new Bound(getValue(), getProtocol(), owner, wacn, system, configuration, nativeScope);
    }

    /** Allocation-free validation for fixed-source channel projections and realtime observers. */
    public final boolean matchesSource(Identifier<?> source)
    {
        return mOwner != null && source instanceof RadioIdentifier radio &&
            sameRadio(mOwner, radio, mServingWacn, mServingSystem);
    }

    public final boolean matchesSource(Identifier<?> source, String servingSystemKey)
    {
        return matchesSource(source, servingSystemKey, null);
    }

    public final boolean matchesSource(Identifier<?> source, String servingSystemKey, String configurationId)
    {
        if(!matchesSource(source)) return false;
        if(RadioSystemKey.isChannelScoped(servingSystemKey)) servingSystemKey = null;
        boolean p25 = RadioSystemKey.isP25Native(servingSystemKey);
        return !scopeConflict(p25 ? Integer.parseInt(servingSystemKey, 4, 9, 16) : -1,
            p25 ? Integer.parseInt(servingSystemKey, 10, 13, 16) : -1, configurationId, (RadioIdentifier)source, p25 ? null : servingSystemKey);
    }

    public final boolean matchesContext(IdentifierCollection context)
    {
        if(context == null || mOwner == null || !isValid()) return false;
        int wacn = networkValue(context, Form.WACN);
        int system = networkValue(context, Form.SYSTEM);
        Identifier source = source(context);
        return source instanceof RadioIdentifier radio &&
            sameRadio(mOwner, radio, wacn >= 0 ? wacn : mServingWacn,
                system >= 0 ? system : mServingSystem) &&
            !scopeConflict(wacn, system, configurationId(context), radio, null);
    }

    /** Returns only a name whose owner agrees with the actual source and available captured serving context. */
    public static TalkerAliasIdentifier find(IdentifierCollection context)
    {
        if(context == null) return null;
        Identifier alias = context.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
        return alias instanceof TalkerAliasIdentifier talkerAlias && talkerAlias.matchesContext(context) ?
            talkerAlias : null;
    }

    public static String valueFor(IdentifierCollection context)
    {
        TalkerAliasIdentifier alias = find(context);
        return alias != null ? alias.getValue() : null;
    }

    private boolean scopeConflict(int wacn, int system, String configurationId, RadioIdentifier candidate,
                                  String nativeSystemKey)
    {
        if(mNativeSystemKey != null && nativeSystemKey != null && !mNativeSystemKey.equals(nativeSystemKey))
            return true;
        //An exact canonical subscriber remains the same radio while roaming. A local address alone cannot supply
        //this exception, and partial contradictory system observations still fail closed.
        if(wacn >= 0 && system >= 0 && mOwner instanceof FullyQualifiedRadioIdentifier &&
            candidate instanceof FullyQualifiedRadioIdentifier && sameRadio(mOwner, candidate, wacn, system))
        {
            return false;
        }
        if(protocol(getProtocol()) == Protocol.APCO25)
        {
            if(wacn >= 0 && mServingWacn >= 0 && wacn != mServingWacn) return true;
            if(system >= 0 && mServingSystem >= 0 && system != mServingSystem) return true;
            if(wacn >= 0 && system >= 0 && mServingWacn >= 0 && mServingSystem >= 0) return false;
        }
        return mConfigurationId != null && configurationId != null &&
            !mConfigurationId.equals(configurationId);
    }

    private static Identifier source(IdentifierCollection context)
    {
        return context != null ? context.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM) : null;
    }

    private static int networkValue(IdentifierCollection context, Form form)
    {
        Identifier value = context != null ? context.getIdentifier(IdentifierClass.NETWORK, form, Role.BROADCAST) : null;
        return value != null && value.getValue() instanceof Integer number ? number : -1;
    }

    private static String configurationId(IdentifierCollection context)
    {
        Identifier value = context != null ?
            context.getIdentifier(IdentifierClass.CONFIGURATION, Form.UNIQUE_ID, Role.ANY) : null;
        return value != null && value.getValue() instanceof String id ? id : null;
    }

    private static boolean usable(RadioIdentifier radio)
    {
        if(radio == null || radio.getRole() != Role.FROM || !radio.isValid() || radio.getProtocol() == null)
        {
            return false;
        }
        if(radio instanceof FullyQualifiedRadioIdentifier qualified)
        {
            return qualified.getWacn() >= 0 && qualified.getWacn() <= 0xFFFFF &&
                qualified.getSystem() >= 0 && qualified.getSystem() <= 0xFFF &&
                qualified.getRadio() > 0 && qualified.getRadio() <= 0xFFFFFC;
        }
        return radio.getValue() != null && radio.getValue() > 0 &&
            (protocol(radio.getProtocol()) != Protocol.APCO25 || radio.getValue() <= 0xFFFFFC);
    }

    private static Protocol protocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
    }

    private static boolean sameRadio(RadioIdentifier owner, RadioIdentifier candidate, int wacn, int system)
    {
        if(!usable(owner) || !usable(candidate) || protocol(owner.getProtocol()) != protocol(candidate.getProtocol()))
        {
            return false;
        }
        if(owner instanceof NXDNRadioIdentifier left && candidate instanceof NXDNRadioIdentifier right &&
            left.isTypeD() != right.isTypeD()) return false;
        if(owner instanceof FullyQualifiedRadioIdentifier left && candidate instanceof FullyQualifiedRadioIdentifier right)
        {
            return left.getWacn() == right.getWacn() && left.getSystem() == right.getSystem() &&
                left.getRadio() == right.getRadio();
        }
        if(owner instanceof FullyQualifiedRadioIdentifier || candidate instanceof FullyQualifiedRadioIdentifier)
        {
            FullyQualifiedRadioIdentifier qualified = owner instanceof FullyQualifiedRadioIdentifier fq ? fq :
                (FullyQualifiedRadioIdentifier)candidate;
            RadioIdentifier local = owner instanceof FullyQualifiedRadioIdentifier ? candidate : owner;
            Integer working = qualified.getWorkingAddress();
            return working != null ? working.equals(local.getValue()) :
                wacn == qualified.getWacn() && system == qualified.getSystem() &&
                    qualified.getRadio() == local.getValue();
        }
        return owner.getValue().equals(candidate.getValue());
    }

    @Override
    public boolean equals(Object other)
    {
        return this == other || other != null && getClass() == other.getClass() &&
            other instanceof TalkerAliasIdentifier alias && Objects.equals(getValue(), alias.getValue()) &&
            getProtocol() == alias.getProtocol() && IdentifierObservation.same(mOwner, alias.mOwner) &&
            mServingWacn == alias.mServingWacn && mServingSystem == alias.mServingSystem &&
            Objects.equals(mConfigurationId, alias.mConfigurationId) &&
            Objects.equals(mNativeSystemKey, alias.mNativeSystemKey);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getValue(), getProtocol(), mOwner, mServingWacn, mServingSystem, mConfigurationId, mNativeSystemKey);
    }

    private static final class Bound extends TalkerAliasIdentifier
    {
        private final Protocol mProtocol;

        private Bound(String value, Protocol protocol, RadioIdentifier owner, int wacn, int system, String configuration,
                      String nativeSystemKey)
        {
            super(value, owner, wacn, system, configuration, nativeSystemKey);
            mProtocol = protocol;
        }

        @Override
        public Protocol getProtocol()
        {
            return mProtocol;
        }

        @Override
        public String toString()
        {
            return protocol(mProtocol) == Protocol.APCO25 || mProtocol == Protocol.DMR ?
                "TA-" + getValue() : getValue();
        }
    }
}
