package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

final class ScopedStorageSaveTest {
    @TempDir Path directory;
    static final UUID ACCOUNT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private byte[] png() throws Exception { return Files.readAllBytes(Path.of("src/test/resources/cpm/classic-a.png")); }
    private LibraryService library(NclSkinsStorage storage) {
        var adapter=new LibraryStorageAdapter(storage);return new LibraryService(adapter,adapter,Clock.systemUTC());
    }
    private static boolean at(String method) {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(f ->
                f.getClassName().equals(AtomicFileWriter.class.getName()) && f.getMethodName().equals(method)));
    }
    private static long temporaryFiles(Path root) throws IOException {
        try(var files=Files.walk(root)) { return files.filter(p->p.getFileName().toString().endsWith(".tmp")).count(); }
    }

    @Test void scopedSaveRechecksAtImmutablePublicationAfterTemporaryWrite() throws Exception {
        var storage=new NclSkinsStorage(directory,new PngValidator(),Clock.systemUTC());storage.initialize();
        var service=library(storage);var reached=new AtomicInteger();var sawTemporary=new AtomicBoolean();
        var before=files(directory);
        assertThrows(IOException.class,()->service.savePresetWithPersonalSkin(ACCOUNT,Optional.empty(),"Selected","Selected",
                SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),OuterLayerVisibility.allVisible(),null,null,()->{
                    if(at("createImmutable") && reached.incrementAndGet()==2) {
                        try { sawTemporary.set(temporaryFiles(directory)==1); } catch(IOException failure) { throw new AssertionError(failure); }
                        return false;
                    }
                    return true;
                }));
        assertEquals(2,reached.get());assertTrue(sawTemporary.get(),"Rejection reaches actual immutable move, not an earlier guard");
        assertEquals(before,files(directory));assertEquals(0,temporaryFiles(directory));
        assertTrue(storage.loadOrCreateAccount(ACCOUNT).presets().isEmpty());
    }

    @Test void scopedSaveRechecksEachBackupAndStateAtomicPublication() throws Exception {
        for(int boundary:List.of(1,2)) {
            Path root=directory.resolve("boundary-"+boundary);
            var storage=new NclSkinsStorage(root,new PngValidator(),Clock.systemUTC());var service=library(storage);
            var saved=service.savePresetWithPersonalSkin(ACCOUNT,Optional.empty(),"First","Original",SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),null);
            service.savePresetWithPersonalSkin(ACCOUNT,Optional.of(saved.preset().id()),"Second","Other",SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),null);
            byte[] oldState=Files.readAllBytes(storage.layout().accountState(ACCOUNT));
            byte[] oldBackup=Files.readAllBytes(storage.layout().accountBackup(ACCOUNT));
            var reached=new AtomicInteger();var sawTemporary=new AtomicBoolean();
            assertThrows(IOException.class,()->service.savePresetWithPersonalSkin(ACCOUNT,Optional.of(saved.preset().id()),"Rejected","Other",
                    SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),OuterLayerVisibility.allVisible(),null,null,()->{
                        if(at("replace") && reached.incrementAndGet()==boundary) {
                            try { sawTemporary.set(temporaryFiles(root)==1); } catch(IOException failure) { throw new AssertionError(failure); }
                            return false;
                        }
                        return true;
                    }));
            assertEquals(boundary,reached.get());assertTrue(sawTemporary.get());
            assertArrayEquals(oldState,Files.readAllBytes(storage.layout().accountState(ACCOUNT)));
            assertArrayEquals(boundary==1?oldBackup:oldState,Files.readAllBytes(storage.layout().accountBackup(ACCOUNT)),"Already committed backup is not rolled back");
            assertEquals(0,temporaryFiles(root));
            assertEquals(1,storage.loadOrCreateAccount(ACCOUNT).presets().size());
        }
    }

    @Test void scopedSameAccountReuseKeepsOneAssetAndOriginalCardName() throws Exception {
        var storage=new NclSkinsStorage(directory,new PngValidator(),Clock.systemUTC());var service=library(storage);
        var first=service.savePresetWithPersonalSkin(ACCOUNT,Optional.empty(),"First","Original",SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),OuterLayerVisibility.allVisible(),null,null,()->true);
        var second=service.savePresetWithPersonalSkin(ACCOUNT,Optional.empty(),"Second","Ignored duplicate",SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),OuterLayerVisibility.allVisible(),null,null,()->true);
        assertEquals(first.asset().id(),second.asset().id());assertEquals(first.personalSkin().displayName(),second.personalSkin().displayName());
        assertEquals(1,second.state().skinAssets().size());assertEquals(1,second.state().personalSkins().size());assertEquals(2,second.state().presets().size());
        assertEquals(List.of("First","Second"),second.state().presets().stream().map(AppearancePreset::name).toList());
    }

    @Test void ordinarySaveRetainsExistingPortImplementationsWithoutGuardedOverloads() throws Exception {
        var storage=new NclSkinsStorage(directory,new PngValidator(),Clock.systemUTC());
        var adapter=new LibraryStorageAdapter(storage);
        com.naocraftlab.skins.core.service.AssetStorePort assets=new com.naocraftlab.skins.core.service.AssetStorePort() {
            public Asset storeAsset(byte[] bytes) throws IOException,com.naocraftlab.skins.core.png.PngValidationException {return adapter.storeAsset(bytes);}
            public byte[] readAsset(String hash) throws IOException,com.naocraftlab.skins.core.png.PngValidationException {return adapter.readAsset(hash);}
        };
        var states=(com.naocraftlab.skins.core.service.LibraryStatePort)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{com.naocraftlab.skins.core.service.LibraryStatePort.class},(proxy,method,args)->{
                    if(method.getName().equals("updateAccount") && args.length==4)return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
                    try {return method.invoke(adapter,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
        var service=new LibraryService(states,assets,Clock.systemUTC());
        var saved=service.savePresetWithPersonalSkin(ACCOUNT,Optional.empty(),"Ordinary","Original",SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),null);
        assertEquals(1,saved.state().presets().size());assertEquals(1,saved.state().personalSkins().size());
        assertArrayEquals(png(),storage.readAsset(saved.asset().sha256()));
    }

    @Test void scopedRetiredSaveDoesNotInitializeStorage() throws Exception {
        Path root=directory.resolve("not-created");var service=library(new NclSkinsStorage(root,new PngValidator(),Clock.systemUTC()));
        assertThrows(IOException.class,()->service.savePresetWithPersonalSkin(ACCOUNT,Optional.empty(),"Selected","Selected",SkinVariant.CLASSIC,PersonalSkinSource.FILE,png(),OuterLayerVisibility.allVisible(),null,null,()->false));
        assertFalse(Files.exists(root));
    }

    private static Map<String,String> files(Path root) throws Exception {
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
