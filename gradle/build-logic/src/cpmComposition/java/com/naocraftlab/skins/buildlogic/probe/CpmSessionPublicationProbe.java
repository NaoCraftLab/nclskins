package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.service.*;
import com.naocraftlab.skins.core.api.PublicHttpsImageFetcher;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.runtime.composition.ClientCompositionRoot;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.objectweb.asm.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CpmSessionPublicationProbe {
    private static final UUID PROFILE=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PLACEHOLDER="synthetic-non-secret-placeholder";
    private static final byte[] WIRE={0,1,2,3,4,5};
    private static int groups;
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("setup")) { host(Path.of(args[1]));return; }
        Class<?> user=Class.forName("net.minecraft.client.User");
        Class<?> source=Class.forName("com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource");
        require(user.getProtectionDomain().getCodeSource()!=null,"Original User has loaded archive origin");
        System.out.println("ORIGINAL_USER_ORIGIN "+user.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("NATIVE_ADAPTER_ORIGIN "+source.getProtectionDomain().getCodeSource().getLocation());
        for(String scenario:List.of("accepted-negative","accepted-positive","fresh-user","blank-after-connect","no-consent","absent","invalid-name","blank-token","mojang-rejected","paste-rejected","session-empty","session-null","session-array","session-malformed","session-error","connect-id","connect-base64","list-schema","count","size","upload-schema","expired","name-connect","name-join","name-session","name-list","account","aba","cancel","close","payload","decline","duplicate","not-sent","uncertain")) {
            run(scenario);groups++;System.out.println("PASS "+scenario);
        }
        System.out.println("SESSION_GROUPS_PASS "+groups);
    }
    private static Object user(String name,String token,UUID profile) throws Exception {
        Class<?> type=Class.forName("net.minecraft.client.User");
        Constructor<?> constructor=Arrays.stream(type.getConstructors()).filter(c->c.getParameterCount()>=5).findFirst().orElseThrow();
        Class<?>[] types=constructor.getParameterTypes();Object[] values=new Object[types.length];
        values[0]=name;values[1]=types[1]==UUID.class?profile:profile.toString().replace("-","");values[2]=token;
        values[3]=Optional.empty();values[4]=Optional.empty();
        if(types.length==6) {
            values[5]=types[5].getEnumConstants()[0];
            for(Object value:types[5].getEnumConstants()) if(value.toString().equalsIgnoreCase("msa"))values[5]=value;
        }
        return constructor.newInstance(values);
    }
    private static void current(Object user) throws Exception { Class.forName("net.minecraft.client.Minecraft").getField("current").set(null,user); }
    private static final class TracedSource implements GameSessionTokenSource {
        final GameSessionTokenSource original;
        int reads;
        TracedSource() throws Exception { original=(GameSessionTokenSource)Class.forName("com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource").getConstructor().newInstance(); }
        public SessionIdentity currentSession(){return original.currentSession();}
        public <T,E extends Exception>T withAccessToken(TokenRequest<T,E> request)throws E{reads++;return original.withAccessToken(request);}
        public <T,E extends Exception>T withSession(SessionRequest<T,E> request)throws E{reads++;return original.withSession(request);}
    }
    private static void run(String scenario) throws Exception {
        current(user("Player",scenario.equals("blank-token")?"":PLACEHOLDER,PROFILE));
        TracedSource source=new TracedSource();ArrayDeque<Runnable> queue=new ArrayDeque<>();AtomicBoolean queued=new AtomicBoolean();
        AtomicLong time=new AtomicLong();List<String> effects=new ArrayList<>();
        AccountState account=new AccountState(AccountState.CURRENT_SCHEMA_VERSION,PROFILE,List.of(),List.of(),Instant.EPOCH);
        SessionValidation offline=new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,new GameSessionTokenSource.SessionIdentity(PROFILE,"Player"),null,(SessionFailureContext)null,"synthetic");
        ClientOperations.InitialData initial=new ClientOperations.InitialData(account,offline,Optional.empty(),Optional.empty(),Optional.empty(),false,List.of());
        ClientOperations bootstrap=(ClientOperations)Proxy.newProxyInstance(CpmSessionPublicationProbe.class.getClassLoader(),new Class<?>[]{ClientOperations.class},(p,m,a)-> {
            if(m.getName().equals("sessionIdentity"))return source.currentSession();
            if(m.getName().equals("initialize")||m.getName().equals("initializeForRuntime"))return initial;
            if(m.getName().equals("uiPreferences"))return AccountUiPreferences.defaults(PROFILE);
            if(m.isDefault())return InvocationHandler.invokeDefault(p,m,a);
            Class<?> result=m.getReturnType();
            if(result==void.class)return null;if(result==boolean.class)return false;if(result==long.class)return 0L;if(result==int.class)return 0;
            if(result==Optional.class)return Optional.empty();if(result==List.class)return List.of();if(result==Map.class)return Map.of();if(result==Set.class)return Set.of();
            throw new AssertionError("Unexpected bootstrap operation "+m.getName());
        });
        try(ClientRuntime runtime=new ClientRuntime(new DefaultRuntimeServices(),bootstrap,new ClientExecutor(){
            public void execute(Runnable action){action.run();}public boolean isClientThread(){return true;}
        },()->CompletableFuture.completedFuture(Optional.empty()),action->{if(queued.get())queue.add(action);else action.run();},UiMessage::key,Optional.empty(),DiagnosticSinks.discarding())) {
            String id=scenario.equals("accepted-positive")?"synthetic-review-session-1":"synthetic-review-session-0";
            runtime.useCpmModelPublication(ClientCompositionRoot.cpmPublication(source,(method,uri,headers,body,timeout,maximum)-> {
                if(uri.getHost().equals("sessionserver.mojang.com")) {
                    effects.add("join");require(uri.toString().equals("https://sessionserver.mojang.com/session/minecraft/join")&&method.equals("POST"),"Fixed Mojang terminal");
                    var json=new com.google.gson.JsonParser().parse(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    require(json.keySet().equals(Set.of("accessToken","selectedProfile","serverId")),"Exact join schema");
                    require(json.get("selectedProfile").getAsString().equals("00000000000000000000000000000001"),"Native current UUID32");
                    require(json.get("accessToken").getAsString().equals(scenario.equals("fresh-user")?"synthetic-reacquired-placeholder":PLACEHOLDER),"Native synthetic token only at Mojang");
                    require(json.get("serverId").getAsString().equals(scenario.equals("accepted-positive")?"6b677988f505b9652c6e8e3bed1e84be7e909a10":"-2a3ef65ec80ef83285c6be6e50acda2f0268f8d2"),"Independent signed vector");
                    require(headers.get("Content-Length").equals(Integer.toString(body.length))&&headers.get("Content-Type").equals("application/json"),"Exact join metadata");
                    if(scenario.equals("name-join"))try{current(user("Changed",PLACEHOLDER,PROFILE));}catch(Exception failure){throw new AssertionError(failure);}
                    return new PublicHttpsImageFetcher.Response(scenario.equals("mojang-rejected")?403:204,new byte[0],Map.of());
                }
                String step=uri.getPath().substring(5);effects.add(step);
                require(uri.getHost().equals("paste.tom5454.com") && uri.getScheme().equals("https"),"Fixed Paste origin");
                require(!headers.containsKey("Cookie")&&!headers.containsKey("Authorization"),"Paste has no Minecraft credential");
                require(timeout.toMillis()>0 && timeout.toMillis()<=30000,"Finite request budget");
                if(step.equals("upload")) {
                    require(method.equals("POST") && headers.get("File-Name").equals("synthetic 😀.cpmmodel"),"Exact upload metadata");
                    require(Arrays.equals(body,"AAECAwQF".getBytes(StandardCharsets.US_ASCII)),"Independent exact wire body");
                    require(headers.get("Content-Length").equals("8"),"Encoded length not binary length");
                    require(effects.equals(List.of("connect","join","session","list","upload")),"Both acceptances before model bytes");
                    if(scenario.equals("not-sent")||scenario.equals("uncertain"))throw new PublicHttpsImageFetcher.FixedRequestException(scenario.equals("uncertain"));
                } else require(method.equals("GET")&&body.length==0,"No model in proof/list");
                if(!step.equals("connect"))require(headers.get("Session").equals(id),"Scoped session header");
                try {
                    if(step.equals("connect") && (scenario.equals("fresh-user")||scenario.equals("blank-after-connect")))current(user("Player",scenario.equals("fresh-user")?"synthetic-reacquired-placeholder":"",PROFILE));
                    if(scenario.equals("name-"+step))current(user("Changed",PLACEHOLDER,PROFILE));
                    if(scenario.equals("account")&&step.equals("session"))current(user("Other",PLACEHOLDER,new UUID(0,2)));
                    if(scenario.equals("aba")&&step.equals("session")){current(user("Other",PLACEHOLDER,new UUID(0,2)));runtime.tick();current(user("Player",PLACEHOLDER,PROFILE));runtime.tick();}
                    if(scenario.equals("close")&&step.equals("session"))runtime.closeScreen();
                    if(scenario.equals("payload")&&step.equals("session"))runtime.publishCpmModel(new CpmModelPublication.Request(new byte[]{9},2,"synthetic 😀.cpmmodel"),true);
                    if(scenario.equals("decline")&&step.equals("session"))runtime.publishCpmModel(new CpmModelPublication.Request(WIRE,1,"synthetic 😀.cpmmodel"),false);
                }catch(Exception failure){throw new AssertionError(failure);}
                if(scenario.equals("expired")&&step.equals("session"))time.set(Duration.ofSeconds(120).toNanos());
                String response=switch(step){
                    case "connect" -> "{\"id\":\""+(scenario.equals("connect-id")?"bad id":id)+"\",\"key\":\""+(scenario.equals("connect-base64")?"!":"AAECAwQFBgcICQoLDA0ODw==")+"\"}";
                    case "session" -> switch(scenario){case "session-empty"->"";case "session-null"->"null";case "session-array"->"[]";case "session-malformed"->"{";case "session-error"->"{\"error\":\"denied\"}";default->"{}";};
                    case "list" -> scenario.equals("list-schema")?"{}":"{\"files\":[],\"maxSize\":"+(scenario.equals("size")?7:8)+",\"maxFiles\":"+(scenario.equals("count")?0:1)+"}";
                    case "upload" -> scenario.equals("upload-schema")?"{}":"{\"id\":\"synthetic-public-id\"}";
                    default -> throw new AssertionError("Unapproved path");
                };
                return new PublicHttpsImageFetcher.Response(scenario.equals("paste-rejected")&&step.equals("session")?403:200,response.getBytes(StandardCharsets.UTF_8),Map.of());
            },time::get));
            runtime.initialize();runtime.dispatchWidget("gallery.add");
            require(runtime.snapshot().addSource().isPresent(),"Actual runtime/import lifetime opened");
            if(scenario.equals("absent"))current(null);if(scenario.equals("invalid-name"))current(user("bad name",PLACEHOLDER,PROFILE));
            if(scenario.equals("cancel"))queued.set(true);
            var request=new CpmModelPublication.Request(WIRE,1,"synthetic 😀.cpmmodel");var result=runtime.publishCpmModel(request,!scenario.equals("no-consent"));
            if(scenario.equals("cancel")){runtime.cancelCpmPublication();queued.set(false);while(!queue.isEmpty())queue.remove().run();}
            var outcome=result.join();
            if(scenario.startsWith("accepted")||scenario.equals("duplicate")||scenario.equals("fresh-user")) {
                require(outcome.status()==CpmModelPublication.Status.CONFIRMED,"Accepted proof reaches one correct upload");require(effects.size()==5 && source.reads==1,"One fresh callback and one upload");
                if(scenario.equals("duplicate")){require(runtime.publishCpmModel(request,true).join().status()==CpmModelPublication.Status.CONFIRMED,"Confirmed link reuse");require(effects.size()==5,"No upload replay");}
            } else if(scenario.equals("uncertain")||scenario.equals("upload-schema"))require(outcome.status()==CpmModelPublication.Status.UNCERTAIN,"Possible send remains uncertain");
            else {require(outcome.status()==CpmModelPublication.Status.NOT_SENT,"Rejected/stale proof cannot publish");if(!scenario.equals("not-sent"))require(!effects.contains("upload"),"Zero model upload");}
            if(List.of("no-consent","absent","invalid-name","cancel").contains(scenario))require(effects.isEmpty()&&source.reads==0,"Zero auth and model effects");
            if(scenario.equals("blank-token"))require(effects.equals(List.of("connect")),"Prior connect is honestly retained");
            if(scenario.equals("absent"))current(user("Player",PLACEHOLDER,PROFILE));
        }
    }
    private static void host(Path root) throws Exception {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);String owner="net/minecraft/client/Minecraft";
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_VOLATILE,"current","Lnet/minecraft/client/User;",null,null).visitEnd();
        var ctor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        var get=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"getInstance","()L"+owner+";",null,null);get.visitCode();get.visitTypeInsn(Opcodes.NEW,owner);get.visitInsn(Opcodes.DUP);get.visitMethodInsn(Opcodes.INVOKESPECIAL,owner,"<init>","()V",false);get.visitInsn(Opcodes.ARETURN);get.visitMaxs(0,0);get.visitEnd();
        var user=writer.visitMethod(Opcodes.ACC_PUBLIC,"getUser","()Lnet/minecraft/client/User;",null,null);user.visitCode();user.visitFieldInsn(Opcodes.GETSTATIC,owner,"current","Lnet/minecraft/client/User;");user.visitInsn(Opcodes.ARETURN);user.visitMaxs(0,0);user.visitEnd();
        writer.visitEnd();Path target=root.resolve(owner+".class");Files.createDirectories(target.getParent());Files.write(target,writer.toByteArray());
    }
}
