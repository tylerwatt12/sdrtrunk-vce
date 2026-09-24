/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.*;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.web.auth.*;
import io.github.dsheirer.web.http.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingAdminHttpTest
{
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path temporary;

    @Test void protectsCommandsAndNeverReturnsSavedCredentials() throws Exception
    {
        Path database=SdrTrunkDatabasePath.getDatabasePath(temporary);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager=new ConfigurationManager(new StreamingAdministrationServiceTest.TestPreferences(temporary),null,new AliasModel(),null,null);
        WebAccessService access=new WebAccessService(database);
        access.provisionOrResetPrimaryAdmin("fixture-password".toCharArray());
        access.createUser("listener","fixture-password".toCharArray(),AccessTier.USER);
        WebAuthenticationService authentication=new WebAuthenticationService(access);
        WebRequestSecurity security=new WebRequestSecurity(access,authentication);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        try
        {
            manager.init();
            var controller=new StreamingAdminHttpController(new StreamingAdministrationService(manager,false));
            server.createContext(StreamingAdminHttpController.PATH,security.protectApi(WebCapability.ADMIN_STREAMING,controller::handle));
            new WebSessionHttpController(access,authentication,security).register(server);
            server.createContext("/provider", exchange -> {
                byte[] response="OK fixture-stream-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,response.length);
                try(var output=exchange.getResponseBody()) { output.write(response); }
            });
            server.start();
            URI root=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            HttpClient client=HttpClient.newHttpClient();
            assertEquals(401,send(client,root,"GET","",null,null,null).statusCode());
            Login listener=login(client,root,"listener");
            assertEquals(403,send(client,root,"GET","",listener,null,null).statusCode());
            Login admin=login(client,root,"admin");
            var options=send(client,root,"GET","/options",admin,null,null);
            assertEquals(200,options.statusCode());
            JsonNode data=JSON.readTree(options.body()).path("data");
            assertEquals(9,data.path("providers").size());
            String revision=data.path("revision").asText();
            String body=JSON.writeValueAsString(Map.of("revision",revision,"provider","BROADCASTIFY_CALL","settings",Map.of("name","County","api_key","fixture-stream-key")));
            assertEquals(403,send(client,root,"POST","",admin,null,body).statusCode());
            var created=send(client,root,"POST","",admin,admin.csrf(),body);
            assertEquals(201,created.statusCode(),created.body());
            String id=JSON.readTree(created.body()).at("/data/configuration_id").asText();
            var entry=send(client,root,"GET","/"+id,admin,null,null);
            assertEquals(200,entry.statusCode());
            assertFalse(entry.body().contains("fixture-stream-key"));
            assertTrue(entry.body().contains("configured_credentials"));
            String testBody=JSON.writeValueAsString(Map.of("configuration_id",id,"provider","BROADCASTIFY_CALL",
                "settings",Map.of("host",root.resolve("/provider").toString(),"system_id",42)));
            var testResult=send(client,root,"POST","/test",admin,admin.csrf(),testBody);
            assertEquals(200,testResult.statusCode(),testResult.body());
            assertTrue(JSON.readTree(testResult.body()).at("/data/success").asBoolean());
            assertFalse(testResult.body().contains("fixture-stream-key"));
            assertFalse(send(client,root,"GET","/"+id,admin,null,null).body().contains(root.resolve("/provider").toString()));
            assertTrue(entry.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertEquals(409,send(client,root,"PUT","/"+id,admin,admin.csrf(),body).statusCode());
            assertEquals(400,send(client,root,"GET","/"+id+"/aliases?limit=1000",admin,null,null).statusCode());
            assertEquals(400,send(client,root,"GET","/"+id+"/aliases?q=x&q=y",admin,null,null).statusCode());
            assertEquals(400,send(client,root,"PUT","/"+id,admin,admin.csrf(),"{\"revision\":\"1\",\"revision\":\"2\"}").statusCode());
            assertEquals(400,send(client,root,"PUT","/"+id,admin,admin.csrf(),"{\"unexpected\":true}").statusCode());
            assertEquals(400,send(client,root,"POST","/test",admin,admin.csrf(),"{\"provider\":\"RADIORESOLVE\",\"settings\":{}}").statusCode());
        }
        finally
        {
            server.stop(0);executor.shutdownNow();security.close();manager.flushConfiguration();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }
    private record Login(String cookie,String csrf) {}
    private static Login login(HttpClient client,URI root,String name) throws Exception
    {
        var response=client.send(HttpRequest.newBuilder(root.resolve("/api/v1/auth/login"))
            .header("Origin",root.toString()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username",name,"password","fixture-password")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        String cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        return new Login(cookie,JSON.readTree(response.body()).at("/data/csrf_token").asText());
    }
    private static HttpResponse<String> send(HttpClient client,URI root,String method,String suffix,Login login,String csrf,String body) throws Exception
    {
        var request=HttpRequest.newBuilder(root.resolve(StreamingAdminHttpController.PATH+suffix));
        if(login!=null)request.header("Cookie",login.cookie());
        if(csrf!=null)request.header(WebRequestSecurity.CSRF_HEADER_NAME,csrf);
        if(body!=null)request.header("Content-Type","application/json").header("Origin",root.toString());
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
