package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class ScopedEditorSaveTest {
    @TempDir Path directory;
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void scopedSameAccountThenQueuedSwapHasNoStorageEffects() throws Exception {
        byte[] png = Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
        var identity = new AtomicReference<>(new GameSessionTokenSource.SessionIdentity(A, "Fixture"));
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return identity.get(); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> action) { throw new AssertionError("Save must not acquire credentials"); }
        };
        ProfileApi api = (ProfileApi) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ProfileApi.class},(p,m,a)->{throw new AssertionError("Save must not call profile API");});
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var operations = TestClientGraph.operations(tokens, api, storage, (BundledSkinSource) model -> png.clone(), Clock.systemUTC());
        storage.initialize();
        var request = new LibraryEditorPort.EditorSaveRequest(Optional.empty(), "Selected", SkinReference.accountDefault(),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.of(png), Optional.empty(), Optional.of("Selected"))
                .withSaveScope(Optional.of(new LibraryEditorPort.EditorSaveScope(A, 1, () -> true)));
        var saved = operations.saveEditor(request);
        assertEquals(A, saved.account().accountId());
        assertEquals(1, saved.account().presets().size());
        assertEquals(1, saved.account().personalSkins().size());
        assertArrayEquals(png, storage.readAsset(saved.account().skinAssets().get(0).sha256()));
        var before = inventory(directory);
        Runnable queued = () -> assertThrows(IOException.class, () -> operations.saveEditor(request));
        identity.set(new GameSessionTokenSource.SessionIdentity(B, "Other"));
        queued.run();
        assertEquals(before, inventory(directory), "No-tick A to B Save must not write assets, either account or backups");
        assertFalse(Files.exists(storage.layout().accountState(B)));
    }

    @Test void scopedApplicationPredicateReachesBlobBackupAndStatePublication() throws Exception {
        for(String boundary:List.of("blob","backup","state")) {
            byte[] png=Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
            var identity=new AtomicReference<>(new GameSessionTokenSource.SessionIdentity(A,"Fixture"));
            GameSessionTokenSource tokens=new GameSessionTokenSource() {
                public SessionIdentity currentSession(){return identity.get();}
                public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> request){throw new AssertionError("No credentials at storage boundary");}
            };
            ProfileApi api=(ProfileApi)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ProfileApi.class},(p,m,a)->{throw new AssertionError(m.getName());});
            var storage=new NclSkinsStorage(directory.resolve(boundary),new PngValidator(),Clock.systemUTC());storage.initialize();
            var operations=TestClientGraph.operations(tokens,api,storage,(BundledSkinSource) model->png,Clock.systemUTC());
            var initial=new LibraryEditorPort.EditorSaveRequest(Optional.empty(),"Existing",SkinReference.accountDefault(),SkinVariant.CLASSIC,SkinVariant.CLASSIC,Optional.empty(),Optional.of(png),Optional.empty(),Optional.of("Original"))
                    .withSaveScope(Optional.of(new LibraryEditorPort.EditorSaveScope(A,1,()->true)));
            operations.saveEditor(initial);operations.saveEditor(initial);
            var before=inventory(storage.layout().root());
            byte[] accountBefore=Files.readAllBytes(storage.layout().accountState(A));
            var reached=new java.util.concurrent.atomic.AtomicInteger();
            int target=boundary.equals("backup")?1:2;
            var scope=new LibraryEditorPort.EditorSaveScope(A,1,()->{
                boolean publication=StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
                        frame.getClassName().equals("com.naocraftlab.skins.core.storage.AtomicFileWriter")
                                && frame.getMethodName().equals(boundary.equals("blob")?"createImmutable":"replace")));
                if(publication && reached.incrementAndGet()==target)identity.set(new GameSessionTokenSource.SessionIdentity(B,"Other"));
                return true;
            });
            byte[] selected=boundary.equals("blob")?Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-b.png")):png;
            var request=new LibraryEditorPort.EditorSaveRequest(Optional.empty(),"Rejected",SkinReference.accountDefault(),SkinVariant.CLASSIC,SkinVariant.CLASSIC,Optional.empty(),Optional.of(selected),Optional.empty(),Optional.of("Selected"))
                    .withSaveScope(Optional.of(scope));
            assertThrows(IOException.class,()->operations.saveEditor(request));
            assertEquals(target,reached.get(),"Actual application predicate reaches "+boundary+" atomic boundary");
            assertArrayEquals(accountBefore,Files.readAllBytes(storage.layout().accountState(A)));
            assertFalse(Files.exists(storage.layout().accountState(B)));
            if(!boundary.equals("state"))assertEquals(before,inventory(storage.layout().root()));
            else assertArrayEquals(accountBefore,Files.readAllBytes(storage.layout().accountBackup(A)),"Earlier valid backup commit is not rolled back");
        }
    }

    @Test void scopedRetiredLifetimeRejectsSameUuidWithoutWrites() throws Exception {
        byte[] png = Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return new SessionIdentity(A,"Fixture"); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> action) { throw new AssertionError(); }
        };
        ProfileApi api = (ProfileApi) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ProfileApi.class},(p,m,a)->{throw new AssertionError();});
        var storage = new NclSkinsStorage(directory,new PngValidator(),Clock.systemUTC());storage.initialize();
        var operations = TestClientGraph.operations(tokens,api,storage,(BundledSkinSource) model -> png,Clock.systemUTC());
        var before=inventory(directory);
        var request=new LibraryEditorPort.EditorSaveRequest(Optional.empty(),"Selected",SkinReference.accountDefault(),SkinVariant.CLASSIC,SkinVariant.CLASSIC,Optional.empty(),Optional.of(png),Optional.empty(),Optional.of("Selected"))
                .withSaveScope(Optional.of(new LibraryEditorPort.EditorSaveScope(A,1,()->false)));
        assertThrows(IOException.class,()->operations.saveEditor(request));
        assertEquals(before,inventory(directory));
    }

    @Test void scopedNullCapturedIdentityFailsClosedAfterRecovery() throws Exception {
        var identity=new AtomicReference<GameSessionTokenSource.SessionIdentity>();
        ClientOperations operations=(ClientOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ClientOperations.class},(p,m,a)->{
            if(m.getName().equals("sessionIdentity"))return identity.get();
            if(m.getName().equals("onCapeObservation"))return null;
            throw new AssertionError(m.getName());
        });
        ClientExecutor client=new ClientExecutor(){public boolean isClientThread(){return true;}public void execute(Runnable action){action.run();}};
        var runtime=new ClientRuntime(new DefaultRuntimeServices(),operations,client,()->CompletableFuture.completedFuture(Optional.empty()),Runnable::run,UiMessage::key,Optional.empty(),Optional.empty(),DiagnosticSinks.discarding());
        var capture=ClientRuntime.class.getDeclaredMethod("captureImportScope");capture.setAccessible(true);
        var current=ClientRuntime.class.getDeclaredMethod("currentImportScope",CatalogImportFlow.ImportScope.class);current.setAccessible(true);
        var editor=ClientRuntime.class.getDeclaredMethod("captureEditorSaveScope",CatalogImportFlow.ImportScope.class);editor.setAccessible(true);
        var missing=(CatalogImportFlow.ImportScope)capture.invoke(runtime);
        identity.set(new GameSessionTokenSource.SessionIdentity(A,"Fixture"));
        assertEquals(false,current.invoke(runtime,missing));
        var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->editor.invoke(runtime,missing));
        assertInstanceOf(IllegalStateException.class,rejected.getCause());
        var valid=(CatalogImportFlow.ImportScope)capture.invoke(runtime);
        assertEquals(true,current.invoke(runtime,valid));
        var nullSameEpoch=new CatalogImportFlow.ImportScope(null,valid.accountEpoch(),valid.resourceEpoch(),valid.worldEpoch());
        assertEquals(false,current.invoke(runtime,nullSameEpoch));
        var nullEditor=assertThrows(java.lang.reflect.InvocationTargetException.class,()->editor.invoke(runtime,nullSameEpoch));
        assertInstanceOf(IllegalStateException.class,nullEditor.getCause());
        var scope=(LibraryEditorPort.EditorSaveScope)editor.invoke(runtime,valid);
        assertTrue(scope.stillCurrent().getAsBoolean());
        identity.set(null);assertFalse(scope.stillCurrent().getAsBoolean());
    }

    static Map<String,String> inventory(Path root) throws Exception {
        var result=new TreeMap<String,String>();
        try(var files=Files.walk(root)) {
            for(Path path:files.filter(Files::isRegularFile).toList()) {
                if(path.getFileName().toString().endsWith(".lock"))continue;
                result.put(root.relativize(path).toString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
}
