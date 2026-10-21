/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.sdrplay.IControlRsp;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.rsp1a.IControlRsp1a;
import io.github.dsheirer.source.tuner.sdrplay.rsp1b.IControlRsp1b;
import io.github.dsheirer.source.tuner.sdrplay.rsp2.IControlRsp2;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDx.IControlRspDx;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TunerRspSettingBindingsTest
{
    @Test
    void everyRspFamilyUsesItsTypedControlSetterAndPropagatesHardwareFailure() throws Exception
    {
        for(TunerType type: EnumSet.of(TunerType.RSP_1, TunerType.RSP_1A, TunerType.RSP_1B, TunerType.RSP_2,
            TunerType.RSP_DUO_1, TunerType.RSP_DUO_2, TunerType.RSP_DX))
        {
            Class<? extends IControlRsp> controlType = switch(type)
            {
                case RSP_1A -> IControlRsp1a.class;
                case RSP_1B -> IControlRsp1b.class;
                case RSP_2 -> IControlRsp2.class;
                case RSP_DUO_1 -> IControlRspDuoTuner1.class;
                case RSP_DUO_2 -> IControlRspDuoTuner2.class;
                case RSP_DX -> IControlRspDx.class;
                default -> IControlRsp.class;
            };
            List<String> writes = new ArrayList<>();
            boolean[] reject = {false};
            IControlRsp control = (IControlRsp)Proxy.newProxyInstance(controlType.getClassLoader(),
                new Class<?>[]{controlType}, (proxy, method, args) -> {
                    if(method.getName().startsWith("set"))
                    {
                        if(reject[0]) throw new IllegalStateException("expected hardware rejection");
                        writes.add(method.getName());
                    }
                    if(method.getReturnType() == int.class) return 0;
                    if(method.getReturnType() == long.class) return 0L;
                    if(method.getReturnType() == double.class) return 0.0;
                    if(method.getReturnType() == boolean.class) return false;
                    return null;
                });
            RspTunerController<IControlRsp> controller = new RspTunerController<>(control, null)
            {
                @Override public TunerType getTunerType() { return type; }
            };
            var configuration = TunerFactory.getTunerConfiguration(type, "typed-rsp-test");
            Map<String,String> expected = Map.ofEntries(
                Map.entry("sample_rate", "setSampleRate"), Map.entry("baseband_gain_reduction", "setGain"),
                Map.entry("lna", "setGain"), Map.entry("agc_mode", "setAgcMode"),
                Map.entry("rf_notch", "setRfNotch"), Map.entry("dab_notch", "setRfDabNotch"),
                Map.entry("bias_t", "setBiasT"), Map.entry("external_reference", "setExternalReferenceOutput"),
                Map.entry("am_port", "setAmPort"), Map.entry("am_notch", "setAmNotch"),
                Map.entry("hdr_mode", "setHighDynamicRange"), Map.entry("hdr_bandwidth", "setHdrModeBandwidth"),
                Map.entry("antenna", type == TunerType.RSP_2 ? "setAntennaSelection" : "setAntenna"));
            for(var definition: TunerSettingCatalog.specs(configuration))
            {
                String setter = expected.get(definition.id());
                if(setter == null) continue;
                writes.clear();
                definition.apply(controller, configuration);
                assertEquals(List.of(setter), writes, type + ": " + definition.id());
                reject[0] = true;
                assertThrows(IllegalStateException.class, () -> definition.apply(controller, configuration),
                    type + ": " + definition.id() + " must not swallow the native-control failure");
                reject[0] = false;
            }
        }
    }
}
