package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import javax.tools.ToolProvider
import java.lang.reflect.InvocationTargetException
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCombinedBatchTest {
    @Test void manifestAdmissionIsBoundedAndPreservesExactBytes() { verify('manifest') }
    @Test void negativesRejectSuppressedTeardownAndNonExactFailures() { verify('negative') }
    @Test void lifecyclePreservesPrimaryAndEveryCleanupFailure() { verify('lifecycle') }
    @Test void everyCleanupStepFailsClosedWithoutSkippingLaterCleanup() { verify('cleanup') }
    @Test void protocolIsOrderedAndNeverInventsNativePass() { verify('protocol') }

    private static void verify(String group) {
        File directory = new File('build/test-data/combined-batch/' + UUID.randomUUID())
        directory.mkdirs()
        File source = new File(directory, 'BatchContract.java')
        source.setText(CONTRACT, 'UTF-8')
        List<String> arguments = ['--release', '17', '-proc:none', '-d', directory.absolutePath,
                'src/waveyCombinedComposition/java/com/naocraftlab/skins/buildlogic/probe/wavey/CombinedBatchCases.java',
                'src/waveyLoaderComposition/game/java/com/naocraftlab/skins/buildlogic/probe/wavey/CaseLifecycle.java', source.absolutePath]
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream()
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, diagnostics, diagnostics, arguments as String[]), diagnostics.toString('UTF-8'))
        new URLClassLoader([directory.toURI().toURL()] as URL[], ClassLoader.platformClassLoader).withCloseable { loader ->
            try {
                loader.loadClass('com.naocraftlab.skins.buildlogic.probe.wavey.BatchContract')
                        .getMethod('run', String, java.nio.file.Path).invoke(null, group, directory.toPath())
            } catch (InvocationTargetException failure) { throw failure.cause }
        }
    }

    static final String CONTRACT = '''
package com.naocraftlab.skins.buildlogic.probe.wavey;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.naocraftlab.skins.buildlogic.probe.wavey.CombinedBatchCases.*;
public final class BatchContract {
    static int checks;
    static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    static Throwable failure(CaseLifecycle.Action action) {
        try { action.run(); } catch (Exception | Error error) { return error; }
        throw new AssertionError("Expected failure");
    }
    static Case row(Path root, String name, String scenario) {
        return new Case(name, Set.of("no-support", "wrong-material").contains(scenario) ? 1 : 0,
                root.toAbsolutePath().resolve(name), "world", scenario, "foreign-first", "normal", "SMOOTH");
    }
    static String line(Case c) {
        return String.join("\\t", c.name(), Integer.toString(c.expectedExit()), c.storageRoot().toString(),
                c.scope(), c.scenario(), c.order(), c.stage(), c.style()) + "\\n";
    }
    static Manifest roster(Case... rows) { return new Manifest("a".repeat(64), List.of(rows)); }
    static void invalid(Path file, String text) throws Exception {
        Files.writeString(file, text);
        check(failure(() -> readManifest(file)) instanceof IllegalArgumentException, "manifest rejected");
    }
    public static void run(String group, Path root) throws Exception {
        checks = 0;
        switch (group) {
            case "manifest" -> manifest(root);
            case "negative" -> negative(root);
            case "lifecycle" -> lifecycle();
            case "cleanup" -> cleanup(root);
            case "protocol" -> protocol(root);
            default -> throw new AssertionError(group);
        }
        System.out.println("COMBINED_BATCH_PURE_CONTRACT_PASS " + group + " checks=" + checks);
    }
    public static void main(String[] args) throws Exception { run(args[0], Path.of(args[1])); }
    static void manifest(Path root) throws Exception {
        Path file = root.resolve("manifest.tsv"); Case a = row(root,"alpha","live"); Case b = row(root,"beta","no-support");
        String text = line(a) + line(b); Files.writeString(file,text);
        Manifest m = readManifest(file);
        check(m.cases().equals(List.of(a,b)),"ordered exact rows");
        check(m.sha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))),"physical hash");
        check(failure(() -> m.cases().clear()) instanceof UnsupportedOperationException,"immutable manifest");
        check(Arrays.equals(a.arguments(),new String[]{a.storageRoot().toString(),"world","live","foreign-first","normal","SMOOTH"}),"original arguments");
        for (String bad : List.of("", "\\n", line(a)+line(a), line(a)+line(b).replace("beta\\t", "alpha\\t"),
                line(a).replace("world\\t", "unknown\\t"), line(a).replace("live\\t", "unknown\\t"),
                line(a).replace("foreign-first", "foreign-middle"), line(a).replace("normal", "OTHER"),
                line(a).replace("SMOOTH", "OTHER"), line(a).replace("\\t0\\t", "\\t2\\t"),
                line(a).replace("\\t0\\t", "\\t1\\t"), line(b).replace("\\t1\\t", "\\t0\\t"),
                line(a).replace(a.storageRoot().toString(), "relative/alpha"),
                line(a).replace(a.storageRoot().toString(), root.resolve("../alpha").toString()),
                line(a).replace(a.storageRoot().toString(), root.resolve("elsewhere/alpha").toString()),
                line(a).replace("alpha\\t", "../alpha\\t"), line(a).replace("\\n", "\\r\\n"),
                line(a).stripTrailing(), line(a)+"\\n", line(a).replace("SMOOTH", ""))) invalid(file,bad);
        StringBuilder overflow = new StringBuilder();
        for (int i=0;i<513;i++) overflow.append(line(row(root,"case-"+i,"live")));
        invalid(file,overflow.toString()); invalid(file,"x".repeat(1024*1024+1));
        Files.write(file,new byte[]{(byte)0xc3,(byte)0x28,10});
        check(failure(() -> readManifest(file)) instanceof IllegalArgumentException,"strict UTF8");
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = root.resolve("alpha"); Files.createSymbolicLink(link,outside);
        try { invalid(file,line(a)); } finally { Files.delete(link); }
        check(failure(() -> new Case("a",0,root.resolve("a"),"world","no-support","foreign-first","normal","SMOOTH")) instanceof IllegalArgumentException,"constructor outcome");
        check(failure(() -> new Manifest("bad",List.of(a))) instanceof IllegalArgumentException,"hash shape");
        check(failure(() -> roster()) instanceof IllegalArgumentException,"empty record");
        check(failure(() -> roster(a,a)) instanceof IllegalArgumentException,"duplicate record");
    }
    static void negative(Path root) throws Exception {
        Case c = row(root,"negative","no-support");
        List<String> events = new ArrayList<>();
        execute(roster(c), x -> { throw new AssertionError("COMBINED_INTEGRATION_MISSING"); }, x -> List.of(() -> {}), events::add);
        check(events.get(2).equals("COMBINED_BATCH_OUTCOME\\tnegative\\tEXPECTED_REJECTION\\tCOMBINED_INTEGRATION_MISSING"),"exact expected rejection");
        List<Throwable> failures = new ArrayList<>(List.of(new AssertionError("unrelated"),
                new AssertionError("prefix COMBINED_INTEGRATION_MISSING"), new AssertionError("COMBINED_INTEGRATION_MISSING suffix"),
                new AssertionError(new AssertionError("COMBINED_INTEGRATION_MISSING")),
                new IllegalStateException("COMBINED_INTEGRATION_MISSING"), new LinkageError("COMBINED_INTEGRATION_MISSING"),
                new AssertionError("COMBINED_INTEGRATION_MISSING") {}));
        AssertionError caused = new AssertionError("COMBINED_INTEGRATION_MISSING"); caused.initCause(new IllegalStateException("close")); failures.add(caused);
        AssertionError suppressed = new AssertionError("COMBINED_INTEGRATION_MISSING"); suppressed.addSuppressed(new IllegalStateException("close")); failures.add(suppressed);
        for (Throwable bad : failures) {
            events.clear();
            Throwable got = failure(() -> execute(roster(c,row(root,"later","live")), x -> { if (bad instanceof Error e) throw e; throw (Exception)bad; },
                    x -> List.of(() -> {}),events::add));
            check(got==bad,"original negative throwable identity");
            check(events.size()==2,"no outcome reset next or complete");
        }
        events.clear();
        Throwable normal = failure(() -> execute(roster(c),x -> {},x -> List.of(() -> {}),events::add));
        check(normal instanceof AssertionError && normal.getMessage().equals("COMBINED_EXPECTED_REJECTION_MISSING"),"normal return rejected");
        check(events.size()==2,"normal rejection fails closed");
        for (boolean errorClose : List.of(false,true)) {
            AssertionError rejection = new AssertionError("COMBINED_INTEGRATION_MISSING");
            Throwable close = errorClose ? new LinkageError("resource-close") : new IllegalStateException("resource-close");
            List<Integer> closed = new ArrayList<>(); events.clear();
            Throwable got = failure(() -> execute(roster(c,row(root,"after-close","live")), x -> {
                try (AutoCloseable resource = () -> {closed.add(1); if(close instanceof Error e)throw e; throw (Exception)close;}) {
                    throw rejection;
                }
            }, x -> List.of(() -> closed.add(2)), events::add));
            check(got==rejection,"try-resource rejection identity");
            check(Arrays.equals(got.getSuppressed(),new Throwable[]{close}),"real resource close suppressed");
            check(closed.equals(List.of(1,2)) && events.size()==2,"try-resource close cannot become expected rejection");
        }
        AssertionError positive = new AssertionError("COMBINED_INTEGRATION_MISSING");
        check(failure(() -> execute(roster(row(root,"positive","live")), x -> {throw positive;},x -> List.of(() -> {}),s -> {}))==positive,"positive never accepts rejection");
        Case wrong = row(root,"wrong","wrong-material"); events.clear();
        execute(roster(c,wrong,row(root,"fresh","live")), x -> {if(x.expectedExit()==1) throw new AssertionError("COMBINED_INTEGRATION_MISSING");}, x -> List.of(() -> {}), events::add);
        check(events.get(events.size()-1).equals("COMBINED_BATCH_COMPLETE\\t3\\t1\\t2"),"negative fresh continuation");
    }
    static void lifecycle() throws Exception {
        List<Integer> visited = new ArrayList<>();
        CaseLifecycle.execute(() -> visited.add(1), () -> visited.add(2)); check(visited.equals(List.of(1,2)),"success cleanup");
        Error body = new LinkageError("body"); Exception cleanup = new Exception("cleanup");
        check(failure(() -> CaseLifecycle.execute(() -> {throw body;}, () -> {}))==body,"body identity");
        check(failure(() -> CaseLifecycle.execute(() -> {}, () -> {throw cleanup;}))==cleanup,"cleanup identity");
        check(failure(() -> CaseLifecycle.execute(() -> {throw body;}, () -> {throw cleanup;}))==body,"both identity");
        check(Arrays.equals(body.getSuppressed(),new Throwable[]{cleanup}),"cleanup suppressed");
    }
    static void cleanup(Path root) throws Exception {
        for(int failed=0;failed<5;failed++) {
            final int fault = failed; List<Integer> visited = new ArrayList<>(); List<String> events = new ArrayList<>();
            Error cleanup = new LinkageError("cleanup-"+fault); List<CaseLifecycle.Action> steps = new ArrayList<>();
            for(int i=0;i<5;i++){final int step=i; steps.add(() -> {visited.add(step); if(step==fault) throw cleanup;});}
            Throwable got = failure(() -> execute(roster(row(root,"first","live"),row(root,"next","live")),x -> {},x -> steps, events::add));
            check(got==cleanup,"cleanup stage identity"); check(visited.equals(List.of(0,1,2,3,4)),"all later cleanup attempted");
            check(events.size()==3 && events.get(2).startsWith("COMBINED_BATCH_OUTCOME"),"no reset next or complete on cleanup failure");
        }
        Error primary = new LinkageError("body"); Error first = new AssertionError("closure"); Exception second = new Exception("host");
        List<Integer> visited = new ArrayList<>();
        Throwable got = failure(() -> execute(roster(row(root,"both","live")), x -> {throw primary;}, x -> List.of(
                () -> {visited.add(0);throw first;}, () -> {visited.add(1);throw second;}, () -> visited.add(2)), s -> {}));
        check(got==primary,"body not wrapped"); check(Arrays.equals(primary.getSuppressed(),new Throwable[]{first}),"cleanup primary suppression");
        check(Arrays.equals(first.getSuppressed(),new Throwable[]{second}),"subsequent cleanup suppression"); check(visited.equals(List.of(0,1,2)),"all failed cleanup visited");
        Error capture = new LinkageError("snapshot"); List<String> events = new ArrayList<>();
        check(failure(() -> execute(roster(row(root,"capture","live")),x -> {throw new AssertionError("body reached");},x -> {throw capture;},events::add))==capture,"snapshot identity");
        check(events.size()==2,"snapshot fails closed");
        AssertionError expected = new AssertionError("COMBINED_INTEGRATION_MISSING"); Error close = new LinkageError("reset"); events.clear();
        check(failure(() -> execute(roster(row(root,"negative","no-support")),x -> {throw expected;},x -> List.of(() -> {throw close;}),events::add))==close,"accepted rejection cannot swallow reset failure");
        check(events.size()==3,"negative reset fails closed");
    }
    static void protocol(Path root) throws Exception {
        Case a=row(root,"alpha","live"), b=row(root,"beta","no-support"); List<String> lines = new ArrayList<>();
        execute(roster(a,b),x -> {if(x.expectedExit()==1)throw new AssertionError("COMBINED_INTEGRATION_MISSING");},x -> List.of(() -> {}),lines::add);
        check(lines.equals(List.of("COMBINED_BATCH_MANIFEST\\t"+"a".repeat(64)+"\\t2",
                "COMBINED_BATCH_BEGIN\\talpha\\tPOSITIVE\\tworld\\tlive\\tforeign-first\\tnormal\\tSMOOTH",
                "COMBINED_BATCH_OUTCOME\\talpha\\tPOSITIVE\\tNONE", "COMBINED_BATCH_RESET\\talpha",
                "COMBINED_BATCH_BEGIN\\tbeta\\tEXPECTED_REJECTION\\tworld\\tno-support\\tforeign-first\\tnormal\\tSMOOTH",
                "COMBINED_BATCH_OUTCOME\\tbeta\\tEXPECTED_REJECTION\\tCOMBINED_INTEGRATION_MISSING", "COMBINED_BATCH_RESET\\tbeta",
                "COMBINED_BATCH_COMPLETE\\t2\\t1\\t1")),"exact protocol");
        check(lines.stream().noneMatch(s -> s.contains("COMBINED_COMPOSITION_PASS")),"no forged native pass");
    }
}
'''
}
