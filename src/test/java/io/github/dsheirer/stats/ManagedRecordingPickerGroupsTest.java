package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedRecordingPickerGroupsTest
{
    @Test
    void eachAliasGroupHasItsOwnBoundedPageAndStableContinuation() throws Exception
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        for(int list=7;list<=8;list++)
            for(int id=1;id<=23;id++) rows.add(Map.of("id",list*100+id,"system_key","p25:00001:001",
                "system_name","Regional","group","Fire","alias_list_id",list,"alias_list_name","List "+list));
        var groups=groups(ManagedRecordingPickerGroups.page(rows,"talkgroup",null,0,100,Long.MAX_VALUE));
        assertEquals(2,groups.size(),"Identical group names from different Alias Lists remain distinguishable");
        assertEquals(10,items(groups.getFirst()).size());
        assertEquals(23,groups.getFirst().get("match_count"));
        assertEquals("List 7",groups.getFirst().get("alias_list_name"));
        assertEquals("Fire",groups.getFirst().get("label"));
        String key=(String)groups.getFirst().get("group_key");
        var more=groups(ManagedRecordingPickerGroups.page(rows,"talkgroup",key,10,100,Long.MAX_VALUE));
        assertEquals(1,more.size());
        assertEquals(key,more.getFirst().get("group_key"));
        assertEquals(711,items(more.getFirst()).getFirst().get("id"));
        assertEquals(10,items(more.getFirst()).size());
        assertEquals(20,more.getFirst().get("next_offset"));
        assertEquals(3,items(groups(ManagedRecordingPickerGroups.page(rows,"talkgroup",key,20,10,
            Long.MAX_VALUE)).getFirst()).size());
        assertThrows(StatsApiException.class,()->ManagedRecordingPickerGroups.validateKey("talkgroup:------------------------------------"));
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> groups(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("groups"); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> group)
    { return (List<Map<String,Object>>)group.get("rows"); }
}
