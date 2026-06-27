/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.reference.Encryption;
import io.github.dsheirer.properties.SystemProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary P25 encryption debug CSV logger for RadioResolve field validation builds.
 */
public final class P25EncryptionDebugLogger
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25EncryptionDebugLogger.class);
    private static final String FILE_NAME = "p25_encryption_debug.csv";
    private static final String HEADER = "timestamp,origin,row_key,state,event_type,decoder,lcn,frequency_hz," +
        "timeslot,parent_channel_id,parent_channel_name,row_channel_id,row_channel_name,source_id,source_form," +
        "source_protocol,target_id,target_form,target_protocol,talkgroup_id,radio_id,algorithm_label," +
        "algorithm_decimal,algorithm_hex,key_id_decimal,key_id_hex,encrypted,encryption_raw,identifier_summary\n";
    private static final Map<String,String> LAST_SIGNATURES = new ConcurrentHashMap<>();
    private static Path sLogPath;
    private static boolean sHeaderChecked;

    private P25EncryptionDebugLogger()
    {
    }

    public static void log(String origin, ChannelActivityRow row, Channel parentChannel,
                           IdentifierCollection identifiers, Identifier<?> encryptionIdentifier,
                           DecodeEventType eventType, State state)
    {
        if(row == null)
        {
            return;
        }

        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : row.getSource();
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : row.getTarget();
        Identifier<?> encryption = encryptionIdentifier != null ? encryptionIdentifier :
            identifiers != null ? identifiers.getEncryptionIdentifier() : null;
        EncryptionInfo encryptionInfo = getEncryptionInfo(encryption);
        boolean encrypted = encryptionInfo.encrypted() || state == State.ENCRYPTED ||
            (eventType != null && DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType)) ||
            eventType == DecodeEventType.DATA_CALL_ENCRYPTED;

        if(!encrypted)
        {
            return;
        }

        String signature = signature(origin, row, source, target, encryptionInfo, eventType, state);

        if(signature.equals(LAST_SIGNATURES.put(row.getKey(), signature)))
        {
            return;
        }

        Channel rowChannel = row.getChannel();
        String line = csv(Instant.now().toString()) +
            csv(origin) +
            csv(row.getKey()) +
            csv(state) +
            csv(eventType) +
            csv(row.getDecoder()) +
            csv(row.getLcn()) +
            csv(row.getFrequency()) +
            csv(row.getTimeslot()) +
            csv(parentChannel != null ? parentChannel.getChannelID() : null) +
            csv(parentChannel != null ? parentChannel.getName() : null) +
            csv(rowChannel != null ? rowChannel.getChannelID() : null) +
            csv(rowChannel != null ? rowChannel.getName() : null) +
            csv(source) +
            csv(source != null ? source.getForm() : null) +
            csv(source != null ? source.getProtocol() : null) +
            csv(target) +
            csv(target != null ? target.getForm() : null) +
            csv(target != null ? target.getProtocol() : null) +
            csv(getTalkgroup(source, target)) +
            csv(getRadio(source, target)) +
            csv(encryptionInfo.algorithmLabel()) +
            csv(encryptionInfo.algorithm()) +
            csv(encryptionInfo.algorithm() != null ? toHex(encryptionInfo.algorithm(), 2) : null) +
            csv(encryptionInfo.key()) +
            csv(encryptionInfo.key() != null ? toHex(encryptionInfo.key(), 4) : null) +
            csv(encrypted) +
            csv(encryption) +
            csv(identifierSummary(identifiers)) +
            "\n";

        write(line);
    }

    private static String signature(String origin, ChannelActivityRow row, Identifier<?> source, Identifier<?> target,
                                    EncryptionInfo encryptionInfo, DecodeEventType eventType, State state)
    {
        return origin + "|" + row.getKey() + "|" + state + "|" + eventType + "|" + row.getFrequency() + "|" +
            row.getTimeslot() + "|" + source + "|" + target + "|" + encryptionInfo.algorithm() + "|" +
            encryptionInfo.key() + "|" + row.getChannel();
    }

    private static EncryptionInfo getEncryptionInfo(Identifier<?> encryptionIdentifier)
    {
        if(encryptionIdentifier instanceof EncryptionKeyIdentifier encryptionKeyIdentifier)
        {
            EncryptionKey key = encryptionKeyIdentifier.getValue();

            if(key != null)
            {
                String label = "ALG:" + toHex(key.getAlgorithm(), 2);

                if(key instanceof APCO25EncryptionKey apco25EncryptionKey)
                {
                    Encryption encryption = apco25EncryptionKey.getEncryptionAlgorithm();

                    if(encryption != Encryption.UNKNOWN)
                    {
                        label = encryption.toString();
                    }
                }

                return new EncryptionInfo(key.isEncrypted(), label, key.getAlgorithm(), key.getKey());
            }
        }

        return new EncryptionInfo(false, null, null, null);
    }

    private static String getTalkgroup(Identifier<?> source, Identifier<?> target)
    {
        Identifier<?> talkgroup = target != null && target.getForm() == Form.TALKGROUP ? target :
            source != null && source.getForm() == Form.TALKGROUP ? source : null;
        return talkgroup != null ? talkgroup.toString() : null;
    }

    private static String getRadio(Identifier<?> source, Identifier<?> target)
    {
        Identifier<?> radio = source != null && source.getForm() == Form.RADIO ? source :
            target != null && target.getForm() == Form.RADIO ? target : null;
        return radio != null ? radio.toString() : null;
    }

    private static String identifierSummary(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return null;
        }

        StringJoiner joiner = new StringJoiner("; ");
        List<Identifier> identifierList = identifiers.getIdentifiers();

        for(Identifier<?> identifier: identifierList)
        {
            joiner.add(identifier.getIdentifierClass() + "/" + identifier.getForm() + "/" +
                identifier.getRole() + "=" + identifier);
        }

        return joiner.toString();
    }

    private static synchronized void write(String line)
    {
        try
        {
            Path path = getLogPath();

            if(!sHeaderChecked)
            {
                if(!Files.exists(path) || Files.size(path) == 0)
                {
                    Files.writeString(path, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                }

                sHeaderChecked = true;
            }

            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        }
        catch(IOException ioe)
        {
            LOGGER.warn("Unable to write P25 encryption debug log", ioe);
        }
    }

    private static Path getLogPath()
    {
        if(sLogPath == null)
        {
            sLogPath = SystemProperties.getInstance().getApplicationFolder("logs").resolve(FILE_NAME);
        }

        return sLogPath;
    }

    private static String toHex(int value, int width)
    {
        return String.format("%0" + width + "X", value);
    }

    private static String csv(Object value)
    {
        String text = value != null ? value.toString() : "";
        return "\"" + text.replace("\"", "\"\"") + "\",";
    }

    private record EncryptionInfo(boolean encrypted, String algorithmLabel, Integer algorithm, Integer key)
    {
    }
}
