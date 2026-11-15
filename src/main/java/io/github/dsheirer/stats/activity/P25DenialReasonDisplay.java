/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.p25.reference.DenyReason;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import java.util.Locale;

/** Read-time descriptions of the reason byte in its saved standard or MFID90 code space. */
public final class P25DenialReasonDisplay
{
    private P25DenialReasonDisplay() {}

    public static String format(int code, boolean vendorSpecific)
    {
        if(code < 0 || code > 255) throw new IllegalArgumentException("Denial reason must be an unsigned byte");
        DenyReason reason = vendorSpecific ? DenyReason.fromCustomCode(code, Vendor.MOTOROLA) :
            DenyReason.fromCode(code);
        return switch(reason)
        {
            case REQUESTING_UNIT_NOT_VALID -> "Requesting unit not valid";
            case REQUESTING_UNIT_NOT_AUTHORIZED_FOR_SERVICE -> "Requesting unit not authorized for service";
            case TARGET_UNIT_NOT_VALID -> "Target unit not valid";
            case TARGET_UNIT_NOT_AUTHORIZED_FOR_SERVICE -> "Target unit not authorized for service";
            case TARGET_UNIT_REFUSED_CALL -> "Target unit refused call";
            case TARGET_GROUP_NOT_VALID -> "Target group not valid";
            case TARGET_GROUP_NOT_AUTHORIZED_FOR_SERVICE -> "Target group not authorized for service";
            case INVALID_DIALING -> "Invalid dialing";
            case TELEPHONE_NUMBER_NOT_AUTHORIZED -> "Telephone number not authorized";
            case PSTN_NOT_VALID -> "Telephone network not valid";
            case CALL_TIMEOUT -> "Call timeout";
            case LANDLINE_TERMINATED_CALL -> "Landline terminated call";
            case SUBSCRIBER_UNIT_TERMINATED_CALL -> "Radio terminated call";
            case CALL_PREEMPTED -> "Call preempted";
            case SITE_ACCESS_DENIAL -> "Site access denial";
            case PTT_COLLIDE -> "PTT collision";
            case PTT_BONK -> "PTT bonk";
            case CALL_OPTIONS_NOT_VALID_FOR_SERVICE -> "Call options not valid for service";
            case PROTECTION_SERVICE_OPTION_NOT_VALID -> "Protection service option not valid";
            case DUPLEX_SERVICE_OPTION_NOT_VALID -> "Duplex service option not valid";
            case CIRCUIT_OR_PACKET_MODE_OPTION_NOT_VALID -> "Circuit or packet mode option not valid";
            case SYSTEM_DOES_NOT_SUPPORT_SERVICE -> "System does not support service";
            case SECURE_REQUEST_ON_CLEAR_SUPERGROUP -> "Secure request on clear supergroup";
            case CLEAR_REQUEST_ON_SECURE_SUPERGROUP -> "Clear request on secure supergroup";
            case RESERVED -> String.format(Locale.ROOT, "Reserved reason (0x%02X)", code);
            case USER_OR_SYSTEM_DEFINED -> String.format(Locale.ROOT, "User/system-defined reason (0x%02X)", code);
            case UNKNOWN -> String.format(Locale.ROOT, "Unknown reason (0x%02X)", code);
        };
    }
}
