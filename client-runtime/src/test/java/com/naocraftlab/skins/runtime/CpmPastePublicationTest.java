package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.core.api.PublicHttpsImageFetcher;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(20)
public class CpmPastePublicationTest {
    @Test void metadataDoesNotChangeWireSettlementOrMergeDistinctPayloads() throws Exception {
        Sessions sessions = new Sessions();
        try (Rig rig = new Rig(sessions, false)) {
            rig.expectedName = "Лесная 😀 +.cpmproject";
            var first = rig.start(true).join();
            assertEquals(CpmModelPublication.Status.CONFIRMED, first.status());
            assertEquals(first, rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE, 2, "other.cpmmodel"), true).join());
            assertEquals(5, rig.effects.size());
            assertEquals(1, sessions.reads);
            rig.effects.clear();
            rig.expectedUploadBody = "CQ==".getBytes(StandardCharsets.US_ASCII);
            var distinct = rig.runtime.publishCpmModel(new CpmModelPublication.Request(new byte[]{9}, 3, rig.expectedName), true).join();
            assertEquals(CpmModelPublication.Status.CONFIRMED, distinct.status());
            assertEquals(5, rig.effects.size());
            assertEquals(2, sessions.reads);
        }
    }

    @Test void serverFilenameRefusalNeverShortensOrResendsAllowedName() throws Exception {
        for (int status : new int[]{400, 413}) try (Rig rig = new Rig(new Sessions(), false)) {
            rig.expectedName = "я😀+".repeat(1500) + ".cpmmodel";
            rig.uploadStatus = status;
            rig.uploadBody = "{\"error\":\"synthetic private refusal\"}";
            var result = rig.start(true).join();
            assertEquals(CpmModelPublication.Status.UNCERTAIN, result.status());
            assertEquals(status == 413 ? CpmModelPublication.Reason.SIZE_QUOTA : CpmModelPublication.Reason.PROTOCOL, result.reason());
            assertEquals(result, rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE, 2, "short.cpmmodel"), true).join());
            rig.runtime.view(854, 480, 0, 0); rig.runtime.tick();
            assertEquals(5, rig.effects.size());
            assertEquals(1, rig.sentNames.size());
            assertEquals(rig.expectedName, rig.sentNames.get(0));
        }
    }

    @Test void nativePayloadSizeIsGovernedByPasteQuotaNotRequestFormatCeiling() throws Exception {
        byte[] wire = new byte[16 * 1024 * 1024 + 1];
        var request = assertDoesNotThrow(() -> new CpmModelPublication.Request(wire, 1, "NCL-Skins-model"));
        try (Rig rig = new Rig(new Sessions(), false)) {
            var result = rig.runtime.publishCpmModel(request, true).join();
            assertEquals(CpmModelPublication.Reason.SIZE_QUOTA, result.reason());
            assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
            assertEquals(List.of("/api/connect", "join", "/api/session", "/api/list"), rig.effects);
        }
    }

    static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final byte[] WIRE = { 0, 1, 2, 3, 4, 5 };
    static final class Sessions implements GameSessionTokenSource {
        SessionIdentity identity = new SessionIdentity(PROFILE,"Player");
        String token="synthetic-non-secret-placeholder";
        int reads;
        public SessionIdentity currentSession() { if(identity==null)throw new GameSessionTokenUnavailableException();return identity; }
        public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> request) throws E { reads++; return request.execute(token); }
        public <T,E extends Exception> T withSession(SessionRequest<T,E> request) throws E { reads++;return request.execute(identity,token); }
    }
    public static final class Rig implements AutoCloseable {
        public final GameSessionTokenSource sessions;
        public final ClientRuntime runtime;
        public final AtomicLong nanos=new AtomicLong();
        public final List<String> effects=new ArrayList<>();
        public final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        public String sessionBody="{}",connectBody="{\"id\":\"synthetic-review-session-0\",\"key\":\"AAECAwQFBgcICQoLDA0ODw==\"}",listBody="{\"files\":[],\"maxSize\":8,\"maxFiles\":1}",uploadBody="{\"id\":\"synthetic-public-id\"}";
        public int joinStatus=204,sessionStatus=200,uploadStatus=200;
        public byte[] expectedUploadBody="AAECAwQF".getBytes(StandardCharsets.US_ASCII);
        public Map<String,List<String>> uploadHeaders=Map.of();
        public String expectedName = "NCL-Skins-model";
        public final List<String> sentNames = new ArrayList<>();
        public String failurePath="/api/upload";
        public java.io.IOException exchangeFailure;
        public String expectedSession="synthetic-review-session-0",expectedServerId="-2a3ef65ec80ef83285c6be6e50acda2f0268f8d2";
        public Runnable afterConnect=()->{},afterJoin=()->{},afterSession=()->{},afterList=()->{};
        public Rig(GameSessionTokenSource sessions,boolean queued) throws Exception {
            this.sessions=sessions;
            Class<?> fake=Class.forName("com.naocraftlab.skins.runtime.ClientRuntimeTest$FakeOperations");
            Constructor<?> ctor=fake.getDeclaredConstructor();ctor.setAccessible(true);Object delegate=ctor.newInstance();
            ClientOperations operations=(ClientOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ClientOperations.class},(p,m,a)-> {
                if(m.getName().equals("sessionIdentity")) return sessions.currentSession();
                Method method=fake.getMethod(m.getName(),m.getParameterTypes());method.setAccessible(true);
                try { return method.invoke(delegate,a); } catch(InvocationTargetException e) { throw e.getCause(); }
            });
            runtime=new ClientRuntime(new DefaultRuntimeServices(),operations,new ClientExecutor(){
                public void execute(Runnable r){r.run();} public boolean isClientThread(){return true;}
            },()->CompletableFuture.completedFuture(Optional.empty()), queued?queue::add:Runnable::run,UiMessage::key,Optional.empty(),DiagnosticSinks.discarding());
            runtime.useCpmModelPublication(com.naocraftlab.skins.runtime.composition.ClientCompositionRoot.cpmPublication(sessions,(method,uri,headers,body,timeout,max)-> {
                if(uri.getHost().equals("sessionserver.mojang.com")) {
                    assertFalse(headers.containsKey("File-Name"));
                    assertEquals("https://sessionserver.mojang.com/session/minecraft/join",uri.toString());assertEquals("POST",method);
                    var json=com.google.gson.JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(Set.of("accessToken","selectedProfile","serverId"),json.keySet());
                    assertEquals("00000000000000000000000000000001",json.get("selectedProfile").getAsString());
                    assertEquals("synthetic-non-secret-placeholder",json.get("accessToken").getAsString());
                    assertEquals(expectedServerId,json.get("serverId").getAsString());
                    assertEquals(Integer.toString(body.length),headers.get("Content-Length"));assertEquals("application/json",headers.get("Content-Type"));
                    effects.add("join");afterJoin.run();return new PublicHttpsImageFetcher.Response(joinStatus,new byte[0],Map.of());
                }
                assertEquals("https",uri.getScheme());assertEquals("paste.tom5454.com",uri.getHost());
                assertTrue(timeout.toMillis()>0 && timeout.toMillis()<=30000);assertTrue(max<=262144);
                String path=uri.getPath();effects.add(path);
                assertFalse(headers.containsKey("Cookie"));assertFalse(headers.containsKey("Authorization"));
                if(path.equals("/api/upload")) {
                    assertEquals("POST",method);assertEquals(expectedName,headers.get("File-Name"));
                    sentNames.add(headers.get("File-Name"));
                    assertEquals(Integer.toString(expectedUploadBody.length),headers.get("Content-Length"));assertArrayEquals(expectedUploadBody,body);
                    assertEquals(List.of("/api/connect","join","/api/session","/api/list","/api/upload"),effects);
                } else { assertEquals("GET",method);assertEquals(0,body.length);assertFalse(headers.containsKey("File-Name")); }
                if(!path.equals("/api/connect")) assertEquals(expectedSession,headers.get("Session"));
                String response=switch(path){case "/api/connect"->connectBody;case "/api/session"->sessionBody;case "/api/list"->listBody;case "/api/upload"->uploadBody;default->throw new AssertionError("Unexpected effect");};
                switch(path){case "/api/connect"->afterConnect.run();case "/api/session"->afterSession.run();case "/api/list"->afterList.run();default->{}}
                if(exchangeFailure!=null && path.equals(failurePath)) throw exchangeFailure;
                return new PublicHttpsImageFetcher.Response(path.equals("/api/session")?sessionStatus:path.equals("/api/upload")?uploadStatus:200,response.getBytes(StandardCharsets.UTF_8),path.equals("/api/upload")?uploadHeaders:Map.of());
            },nanos::get));
            runtime.initialize();drain();runtime.dispatchWidget("gallery.add");drain();
            assertTrue(runtime.snapshot().addSource().isPresent(),"Real import lifetime opened");
        }
        public CompletableFuture<CpmModelPublication.Result> start(boolean consent){return runtime.publishCpmModel(new CpmModelPublication.Request(WIRE,1,expectedName),consent);}
        public void drain(){while(!queue.isEmpty())queue.remove().run();}
        public void close(){runtime.close();}
    }
    @Test void absentIdentityHasMissingSessionOutcomeWithoutConnect() throws Exception {
        Sessions sessions=new Sessions();
        try(Rig rig=new Rig(sessions,false)) {
            sessions.identity=null;
            assertEquals(CpmModelPublication.Reason.MISSING_SESSION,rig.start(true).join().reason());assertTrue(rig.effects.isEmpty());assertEquals(0,sessions.reads);
        }
    }
    @Test void sharedPublicationBudgetFencesConnectProofSessionAndQuotaContinuations() throws Exception {
        for (int stage = 0; stage < 4; stage++) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.runtime.importClock(rig.nanos::get);
                Runnable expire = () -> rig.nanos.set(java.time.Duration.ofSeconds(60).toNanos());
                if (stage == 0) rig.afterConnect = expire;
                if (stage == 1) rig.afterJoin = expire;
                if (stage == 2) rig.afterSession = expire;
                if (stage == 3) rig.afterList = expire;
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
                assertEquals(CpmModelPublication.Reason.EXPIRED, result.reason());
                assertEquals(stage + 1, rig.effects.size());
                assertEquals(stage == 0 ? 0 : 1, sessions.reads);
                assertFalse(rig.effects.contains("/api/upload"));
            }
        }
    }

    @Test void publicationDeadlineReportsOwnSendKnowledgeAndLateReplyOnlySettlesItsWire() throws Exception {
        try (Rig rig = new Rig(new Sessions(), true)) {
            rig.runtime.importClock(rig.nanos::get);
            var queued = rig.start(true);
            rig.nanos.set(java.time.Duration.ofSeconds(60).toNanos());
            rig.runtime.view(240, 240, 0, 0);
            assertEquals(CpmModelPublication.Status.NOT_SENT, queued.join().status());
            rig.drain(); assertTrue(rig.effects.isEmpty());
        }
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.runtime.importClock(rig.nanos::get);
            var calls = new AtomicInteger();
            rig.runtime.useCpmModelPublication((request, current) -> {
                calls.incrementAndGet();
                assertTrue(((CpmModelPublication.Control) current).beginSend());
                rig.nanos.set(java.time.Duration.ofSeconds(60).toNanos());
                rig.runtime.tick();
                return new CpmModelPublication.Result(CpmModelPublication.Status.CONFIRMED,
                        CpmModelPublication.Reason.NONE, Optional.of("late-id"));
            });
            assertEquals(CpmModelPublication.Status.UNCERTAIN, rig.start(true).join().status());
            assertEquals("nclskins.add.cpm_publication_uncertain", rig.runtime.snapshot().status().key());
            assertEquals(CpmModelPublication.Status.CONFIRMED,
                    rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "different.cpmmodel"), true).join().status());
            assertEquals(1, calls.get());
            assertEquals("nclskins.add.cpm_publication_uncertain", rig.runtime.snapshot().status().key());
        }
    }

    @Test void declinedConsentRetiresAlreadyQueuedPublication() throws Exception {
        Sessions sessions=new Sessions();
        try(Rig rig=new Rig(sessions,true)) {
            var first=rig.start(true);rig.start(false);rig.drain();
            assertTrue(rig.effects.isEmpty());assertEquals(0,sessions.reads);assertEquals(CpmModelPublication.Status.NOT_SENT,first.join().status());
        }
    }
    @Test void closingImportAfterAcceptedSessionStopsNextEffect() throws Exception {
        try(Rig rig=new Rig(new Sessions(),false)) {
            rig.afterSession=rig.runtime::closeScreen;
            assertNotEquals(CpmModelPublication.Status.CONFIRMED,rig.start(true).join().status());
            assertEquals(List.of("/api/connect","join","/api/session"),rig.effects);
        }
    }
    @Test void rejectedAndMalformedProofNeverUpload() throws Exception {
        for(String body:List.of("", "null", "[]", "{", "{\"error\":\"denied\"}","{\"a\":1,\"a\":2}")) {
            try(Rig rig=new Rig(new Sessions(),false)) {
                rig.sessionBody=body;assertEquals(CpmModelPublication.Status.NOT_SENT,rig.start(true).join().status());
                assertEquals(List.of("/api/connect","join","/api/session"),rig.effects);
            }
        }
        for(int status:List.of(401,403)) try(Rig rig=new Rig(new Sessions(),false)) {
            rig.joinStatus=status;assertEquals(CpmModelPublication.Reason.PROOF_REJECTED,rig.start(true).join().reason());assertEquals(2,rig.effects.size());
        }
    }
    @Test void pasteInvalidSessionIsProofRejectionWithoutModelUpload() throws Exception {
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.sessionBody = "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.invalidSession\"}";
            var result = rig.start(true).join();
            assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
            assertEquals(CpmModelPublication.Reason.PROOF_REJECTED, result.reason());
            assertEquals(List.of("/api/connect", "join", "/api/session"), rig.effects);
        }
    }
    @Test void invalidSessionAtQuotaCheckCannotReachUpload() throws Exception {
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.listBody = "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.invalidSession\",\"files\":[],\"maxSize\":8,\"maxFiles\":1}";
            var result = rig.start(true).join();
            assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
            assertEquals(CpmModelPublication.Reason.PROOF_REJECTED, result.reason());
            assertEquals(List.of("/api/connect", "join", "/api/session", "/api/list"), rig.effects);
        }
    }
    @Test void errorEnvelopeAfterUploadCannotConfirmOrPermitResend() throws Exception {
        for (String message : List.of("error.paste.invalidSession", "synthetic-unknown", "")) {
            try (Rig rig = new Rig(new Sessions(), false)) {
                rig.uploadBody = "{\"id\":\"synthetic-public-id\",\"error\":\"synthetic\",\"errorMessage\":\"" + message + "\"}";
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.UNCERTAIN, result.status());
                assertEquals(message.equals("error.paste.invalidSession") ? CpmModelPublication.Reason.PROOF_REJECTED
                        : CpmModelPublication.Reason.PROTOCOL, result.reason());
                assertTrue(result.pasteId().isEmpty());
                assertEquals(CpmModelPublication.Status.UNCERTAIN,
                        rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "NCL-Skins-model"), true).join().status());
                assertEquals(1, rig.effects.stream().filter("/api/upload"::equals).count());
            }
        }
    }
    @Test void currentNameEpochExpiryAndCancellationFenceNextEffect() throws Exception {
        for(int stage=0;stage<4;stage++) {
            Sessions sessions=new Sessions();try(Rig rig=new Rig(sessions,false)) {
                Runnable change=()->sessions.identity=new GameSessionTokenSource.SessionIdentity(PROFILE,"Changed");
                if(stage==0)rig.afterConnect=change;if(stage==1)rig.afterJoin=change;if(stage==2)rig.afterSession=change;if(stage==3)rig.afterList=change;
                assertEquals(CpmModelPublication.Status.NOT_SENT,rig.start(true).join().status());assertEquals(stage+1,rig.effects.size());
            }
        }
        try(Rig rig=new Rig(new Sessions(),false)) {
            rig.afterSession=()->rig.nanos.set(java.time.Duration.ofSeconds(120).toNanos());
            assertEquals(CpmModelPublication.Reason.EXPIRED,rig.start(true).join().reason());assertEquals(3,rig.effects.size());
        }
        try(Rig rig=new Rig(new Sessions(),true)) {
            var future=rig.start(true);rig.runtime.cancelCpmPublication();rig.drain();assertEquals(CpmModelPublication.Status.NOT_SENT,future.join().status());assertTrue(rig.effects.isEmpty());
        }
    }
    @Test void noConsentBlankTokenAndQuotasHaveHonestEffectCounts() throws Exception {
        Sessions sessions=new Sessions();try(Rig rig=new Rig(sessions,false)) { assertEquals(CpmModelPublication.Reason.NO_CONSENT,rig.start(false).join().reason());assertTrue(rig.effects.isEmpty());assertEquals(0,sessions.reads); }
        sessions=new Sessions();sessions.token="";try(Rig rig=new Rig(sessions,false)) { assertEquals(CpmModelPublication.Reason.MISSING_SESSION,rig.start(true).join().reason());assertEquals(List.of("/api/connect"),rig.effects); }
        for(String list:List.of("{\"files\":[],\"maxSize\":7,\"maxFiles\":1}","{\"files\":[],\"maxSize\":8,\"maxFiles\":0}","{}","{\"files\":[],\"maxSize\":8.5,\"maxFiles\":1}")) try(Rig rig=new Rig(new Sessions(),false)) {
            rig.listBody=list;assertEquals(CpmModelPublication.Status.NOT_SENT,rig.start(true).join().status());assertEquals(4,rig.effects.size());
        }
    }
    @Test void changedPayloadRetiresPriorConsentWhileProofIsBusy() throws Exception {
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.afterSession = () -> rig.runtime.publishCpmModel(new CpmModelPublication.Request(new byte[]{9}, 2, "NCL-Skins-model"), true);
            assertEquals(CpmModelPublication.Status.NOT_SENT, rig.start(true).join().status());
            assertFalse(rig.effects.contains("/api/upload"));
        }
    }
    @Test void settlementCannotCrossAccountChangeWithoutTick() throws Exception {
        Sessions sessions = new Sessions();
        try (Rig rig = new Rig(sessions, false)) {
            assertEquals(CpmModelPublication.Status.CONFIRMED, rig.start(true).join().status());
            sessions.identity = new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Other");
            var result = rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "NCL-Skins-model"), true).join();
            assertEquals(CpmModelPublication.Reason.STALE, result.reason());
            assertTrue(result.pasteId().isEmpty());
            assertEquals(5, rig.effects.size());
        }
    }
    @Test void malformedNumericUploadResponseRemainsUncertain() throws Exception {
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.uploadBody = "{\"id\":1e2147483648}";
            assertEquals(CpmModelPublication.Status.UNCERTAIN, rig.start(true).join().status());
            assertEquals(CpmModelPublication.Status.UNCERTAIN,
                    rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "NCL-Skins-model"), true).join().status());
            assertEquals(1, rig.effects.stream().filter("/api/upload"::equals).count());
        }
    }
    @Test void uncertainPayloadCannotBeSentAgainByConstructingAnotherRequest() throws Exception {
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.uploadBody = "{}";
            assertEquals(CpmModelPublication.Status.UNCERTAIN, rig.start(true).join().status());
            var repeated = rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "different.cpmproject"), true).join();
            assertEquals(CpmModelPublication.Status.UNCERTAIN, repeated.status());
            assertEquals(1, rig.effects.stream().filter("/api/upload"::equals).count());
        }
    }
    @Test void acceptedIndependentSignedVectorThroughRealApplicationCaller() throws Exception {
        Sessions sessions=new Sessions();
        try(Rig rig=new Rig(sessions,false)){
            var result=rig.start(true).join();assertEquals(CpmModelPublication.Status.CONFIRMED,result.status());
            assertEquals("synthetic-public-id",result.pasteId().orElseThrow());assertEquals(1,sessions.reads);assertEquals(5,rig.effects.size());
        }
    }
    @Test void opaqueConnectSessionIsHashedAndForwardedVerbatimRatherThanValidatedAsPublicId() throws Exception {
        Sessions sessions = new Sessions();
        try (Rig rig = new Rig(sessions, false)) {
            rig.expectedSession = "synthetic/session+with=padding==";
            rig.expectedServerId = "-6d39100d37db739a93f40a553303fe56866713e6";
            rig.connectBody = "{\"id\":\"synthetic/session+with=padding==\",\"key\":\"AAECAwQFBgcICQoLDA0ODw==\"}";
            var result = rig.start(true).join();
            assertEquals(CpmModelPublication.Status.CONFIRMED, result.status());
            assertEquals("synthetic-public-id", result.pasteId().orElseThrow());
            assertEquals(1, sessions.reads);
            assertEquals(List.of("/api/connect", "join", "/api/session", "/api/list", "/api/upload"), rig.effects);
            assertEquals(CpmModelPublication.Status.CONFIRMED, rig.start(true).join().status());
            assertEquals(5, rig.effects.size());
        }
    }
    @Test void opaqueSessionAdmissionPreservesAsciiAndInclusiveLengthBounds() throws Exception {
        String printableAscii = java.util.stream.IntStream.rangeClosed(33, 126)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String[][] vectors = {
                {"!", "5340e025f6c1023322bddf799e38a0b16d6e5b80"},
                {"synthetic-" + "~".repeat(1014), "-4eceffd00eb44f3cdbe2a90c952df1ce8428846e"},
                {printableAscii, "51d210cc9b8b6eb5c0f792369a2b0b6d44f6fc9d"}
        };
        for (String[] vector : vectors) {
            try (Rig rig = new Rig(new Sessions(), false)) {
                rig.expectedSession = vector[0];
                rig.expectedServerId = vector[1];
                rig.connectBody = connectResponse(vector[0], "AAECAwQFBgcICQoLDA0ODw==");
                assertEquals(CpmModelPublication.Status.CONFIRMED, rig.start(true).join().status());
                assertEquals(5, rig.effects.size());
            }
        }
    }
    @Test void unsafeOrOversizedSessionIsRejectedBeforeCredentialReadAndJoin() throws Exception {
        for (String id : List.of("", " ", " synthetic", "synthetic ", "synthetic value", "synthetic\rvalue",
                "synthetic\nvalue", "synthetic\tvalue", "synthetic\u0000value", "synthetic\u001fvalue",
                "synthetic\u007fvalue", "synthetic\u0080value", "synthetic\u00e9value",
                "s".repeat(1025))) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.connectBody = connectResponse(id, "AAECAwQFBgcICQoLDA0ODw==");
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
                assertEquals(CpmModelPublication.Reason.PROTOCOL, result.reason());
                assertEquals(List.of("/api/connect"), rig.effects);
                assertEquals(0, sessions.reads);
            }
        }
    }
    @Test void opaqueSessionDoesNotRelaxConnectKeyValidation() throws Exception {
        for (String key : List.of("", "A".repeat(1028), "not-base64!", "AA", "AB==", "AA==\r\n")) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.connectBody = connectResponse("synthetic/session+with=padding==", key);
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
                assertEquals(CpmModelPublication.Reason.PROTOCOL, result.reason());
                assertEquals(List.of("/api/connect"), rig.effects);
                assertEquals(0, sessions.reads);
            }
        }
    }
    @Test void opaqueSessionGrammarCannotAdmitPublicPasteIdsOrEnableUncertainResend() throws Exception {
        for (String id : List.of("synthetic/id", "synthetic+id", "synthetic=id", "synthetic.id", "s".repeat(1025))) {
            try (Rig rig = new Rig(new Sessions(), false)) {
                com.google.gson.JsonObject upload = new com.google.gson.JsonObject();
                upload.addProperty("id", id);
                rig.uploadBody = upload.toString();
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.UNCERTAIN, result.status());
                assertEquals(CpmModelPublication.Reason.PROTOCOL, result.reason());
                assertTrue(result.pasteId().isEmpty());
                assertEquals(CpmModelPublication.Status.UNCERTAIN,
                        rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "NCL-Skins-model"), true).join().status());
                assertEquals(5, rig.effects.size());
            }
        }
    }
    @Test void httpSizeRefusalRetainsUncertainSettlementWithoutResend() throws Exception {
        for (String body : List.of("", "not-json", "{\"id\":\"synthetic-public-id\"}", "x".repeat(65536))) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.uploadStatus = 413;
                rig.uploadBody = body;
                assertUncertainWithoutResend(rig, sessions, CpmModelPublication.Reason.SIZE_QUOTA);
            }
        }
    }
    @Test void protocolSizeDiscriminatorRetainsUncertainSettlementWithoutResend() throws Exception {
        Sessions sessions = new Sessions();
        try (Rig rig = new Rig(sessions, false)) {
            rig.uploadBody = "{\"error\":\"synthetic unrelated text\",\"errorMessage\":\"error.paste.fileTooBig\",\"id\":\"synthetic-public-id\"}";
            assertUncertainWithoutResend(rig, sessions, CpmModelPublication.Reason.SIZE_QUOTA);
        }
    }
    @Test void dynamicSizeQuotaUsesExactEncodedLengthIncludingPadding() throws Exception {
        byte[][] wires = {{0}, {0, 1}, {0, 1, 2}, {0, 1, 2, 3}};
        String[] encoded = {"AA==", "AAE=", "AAEC", "AAECAw=="};
        for (int i = 0; i < wires.length; i++) {
            for (long quota : new long[]{0, encoded[i].length() - 1, encoded[i].length(), encoded[i].length() + 1, Long.MAX_VALUE}) {
                try (Rig rig = new Rig(new Sessions(), false)) {
                    rig.listBody = "{\"files\":[],\"maxSize\":" + quota + ",\"maxFiles\":1}";
                    rig.expectedUploadBody = encoded[i].getBytes(StandardCharsets.US_ASCII);
                    var result = rig.runtime.publishCpmModel(new CpmModelPublication.Request(wires[i], 1, "NCL-Skins-model"), true).join();
                    boolean fits = quota >= encoded[i].length();
                    assertEquals(fits ? CpmModelPublication.Status.CONFIRMED : CpmModelPublication.Status.NOT_SENT, result.status());
                    assertEquals(fits ? CpmModelPublication.Reason.NONE : CpmModelPublication.Reason.SIZE_QUOTA, result.reason());
                    assertEquals(fits ? 5 : 4, rig.effects.size());
                }
            }
        }
    }
    @Test void higherServerQuotaAllowsNativePayloadBeyondFormerRequestCeiling() throws Exception {
        byte[] wire = new byte[16 * 1024 * 1024 + 1];
        try (Rig rig = new Rig(new Sessions(), false)) {
            rig.expectedUploadBody = Base64.getEncoder().encode(wire);
            rig.listBody = "{\"files\":[],\"maxSize\":" + rig.expectedUploadBody.length + ",\"maxFiles\":1}";
            var result = rig.runtime.publishCpmModel(new CpmModelPublication.Request(wire, 1, "NCL-Skins-model"), true).join();
            assertEquals(CpmModelPublication.Status.CONFIRMED, result.status());
            assertEquals(5, rig.effects.size());
        }
    }
    @Test void dynamicCountQuotaAllowsOneFreeSlotButNotAFullAccount() throws Exception {
        for (long quota : new long[]{0, 1, 2, Long.MAX_VALUE}) {
            try (Rig rig = new Rig(new Sessions(), false)) {
                rig.listBody = "{\"files\":[{\"id\":\"synthetic-existing\",\"name\":\"synthetic\",\"time\":\"0\"}],\"maxSize\":8,\"maxFiles\":" + quota + "}";
                var result = rig.start(true).join();
                assertEquals(quota > 1 ? CpmModelPublication.Status.CONFIRMED : CpmModelPublication.Status.NOT_SENT, result.status());
                assertEquals(quota > 1 ? CpmModelPublication.Reason.NONE : CpmModelPublication.Reason.COUNT_QUOTA, result.reason());
                assertEquals(quota > 1 ? 5 : 4, rig.effects.size());
            }
        }
    }
    @Test void malformedQuotaNumbersNeverBecomePermissiveLimits() throws Exception {
        for (String key : List.of("maxSize", "maxFiles")) {
            for (String value : List.of("null", "\"8\"", "true", "-1", "8.5", "9223372036854775808", "1e2147483648", "{}", "[]")) {
                try (Rig rig = new Rig(new Sessions(), false)) {
                    rig.listBody = "{\"files\":[],\"" + key + "\":" + value + ",\"" + (key.equals("maxSize") ? "maxFiles" : "maxSize") + "\":8}";
                    var result = rig.start(true).join();
                    assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
                    assertEquals(CpmModelPublication.Reason.PROTOCOL, result.reason());
                    assertEquals(4, rig.effects.size());
                }
            }
        }
    }
    @Test void unknownAndMalformedSizeLookingResponsesNeverMapByHumanText() throws Exception {
        for (String body : List.of(
                "{\"error\":\"File size too big\",\"errorMessage\":\"synthetic-unknown\"}",
                "{\"error\":\"error.paste.fileTooBig\"}",
                "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.fileTooBig.extra\"}",
                "{\"error\":\"synthetic\",\"errorMessage\":\" error.paste.fileTooBig\"}",
                "{\"error\":\"synthetic\",\"errorMessage\":null}",
                "{\"error\":\"synthetic\",\"errorMessage\":[\"error.paste.fileTooBig\"]}",
                "{\"error\":null,\"errorMessage\":\"error.paste.fileTooBig\"}",
                "{\"error\":true,\"errorMessage\":\"error.paste.fileTooBig\"}",
                "{\"errorMessage\":\"error.paste.fileTooBig\"}",
                "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.fileTooBig\",\"errorMessage\":\"synthetic-unknown\"}",
                "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.fileTooBig\"} trailing",
                "[]", "null", "{", "File size too big")) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.uploadBody = body;
                assertUncertainWithoutResend(rig, sessions, CpmModelPublication.Reason.PROTOCOL);
            }
        }
    }
    @Test void authenticationAndOtherHttpFailuresAreNotSizeRefusalsOrRetried() throws Exception {
        for (int status : new int[]{401, 403, 400, 429, 500, 302}) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.uploadStatus = status;
                rig.uploadBody = "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.fileTooBig\"}";
                assertUncertainWithoutResend(rig, sessions, status == 401 || status == 403
                        ? CpmModelPublication.Reason.PROOF_REJECTED : CpmModelPublication.Reason.PROTOCOL);
            }
        }
    }
    @Test void authenticationFailureBeforeUploadCannotBecomeSizeQuotaOrReadProofAgain() throws Exception {
        for (int status : new int[]{401, 403}) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.sessionStatus = status;
                rig.sessionBody = "{\"error\":\"synthetic\",\"errorMessage\":\"error.paste.fileTooBig\"}";
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
                assertEquals(CpmModelPublication.Reason.PROOF_REJECTED, result.reason());
                assertEquals(List.of("/api/connect", "join", "/api/session"), rig.effects);
                assertEquals(1, sessions.reads);
            }
        }
    }
    @Test void networkFailuresPreservePossibleSendAndDoNotInferSizeFromExceptionText() throws Exception {
        for (java.io.IOException failure : List.of(new java.io.IOException("413 error.paste.fileTooBig"),
                new PublicHttpsImageFetcher.FixedRequestException(true))) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.exchangeFailure = failure;
                assertUncertainWithoutResend(rig, sessions, CpmModelPublication.Reason.NETWORK);
            }
        }
        for (String path : List.of("/api/connect", "/api/session", "/api/list", "/api/upload")) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.failurePath = path;
                rig.exchangeFailure = new PublicHttpsImageFetcher.FixedRequestException(false);
                var result = rig.start(true).join();
                assertEquals(CpmModelPublication.Status.NOT_SENT, result.status());
                assertEquals(CpmModelPublication.Reason.NETWORK, result.reason());
                assertEquals(path, rig.effects.get(rig.effects.size() - 1));
                assertEquals(1, rig.effects.stream().filter(path::equals).count());
                assertEquals(path.equals("/api/connect") ? 0 : 1, sessions.reads);
            }
        }
    }
    @Test void sizeRefusalDoesNotBypassResponseHeadersOrBodyBounds() throws Exception {
        for (String header : List.of("Location", "sEt-CoOkIe")) {
            Sessions sessions = new Sessions();
            try (Rig rig = new Rig(sessions, false)) {
                rig.uploadStatus = 413;
                rig.uploadHeaders = Map.of(header, List.of("synthetic"));
                assertUncertainWithoutResend(rig, sessions, CpmModelPublication.Reason.PROTOCOL);
            }
        }
        Sessions sessions = new Sessions();
        try (Rig rig = new Rig(sessions, false)) {
            rig.uploadStatus = 413;
            rig.uploadBody = "x".repeat(65537);
            assertUncertainWithoutResend(rig, sessions, CpmModelPublication.Reason.PROTOCOL);
        }
    }
    private static void assertUncertainWithoutResend(Rig rig, Sessions sessions,
            CpmModelPublication.Reason reason) {
        var result = rig.start(true).join();
        assertEquals(CpmModelPublication.Status.UNCERTAIN, result.status());
        assertEquals(reason, result.reason());
        assertTrue(result.pasteId().isEmpty());
        assertEquals(List.of("/api/connect", "join", "/api/session", "/api/list", "/api/upload"), rig.effects);
        assertEquals(result, rig.runtime.publishCpmModel(new CpmModelPublication.Request(WIRE.clone(), 2, "different.cpmmodel"), true).join());
        assertEquals(5, rig.effects.size());
        assertEquals(1, sessions.reads);
    }
    private static String connectResponse(String id, String key) {
        com.google.gson.JsonObject connect = new com.google.gson.JsonObject();
        connect.addProperty("id", id);
        connect.addProperty("key", key);
        return connect.toString();
    }
}
