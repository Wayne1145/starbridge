import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Counts sampled server stacks during production hull assembly in a downloaded recording. */
class LandingSamples {
    private static final String ASSEMBLY = "dev.starbridge.compat.vs.VsShipBackend.assembleCabin";
    private static final String VS_MOVE = "org.valkyrienskies.mod.common.assembly.ShipAssembler.moveBlocksFromTo";
    private static final String TILE_LIST = "retrofit.legacy.bridge.WorldGlue.listTile";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass the downloaded wide-berths-server.jfr file");
        }
        int serverSamples = 0;
        int truncatedStacks = 0;
        int assemblySamples = 0;
        int tileListSamples = 0;
        Map<String, Integer> moveCallSites = new HashMap<>();
        Map<String, Integer> assemblyTopFrames = new HashMap<>();
        Map<String, Integer> tileListTopFrames = new HashMap<>();
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                var thread = event.getThread("sampledThread");
                if (thread == null || !"Server thread".equals(thread.getJavaName())) continue;
                serverSamples++;
                var trace = event.getStackTrace();
                if (trace == null) continue;
                if (trace.isTruncated()) truncatedStacks++;
                var frames = trace.getFrames();
                var methods = frames.stream().map(LandingSamples::method).toList();
                if (!methods.contains(ASSEMBLY)) continue;
                assemblySamples++;
                var top = methods.get(0);
                assemblyTopFrames.merge(top, 1, Integer::sum);
                int move = methods.indexOf(VS_MOVE);
                if (move >= 0) {
                    var callSite = "ShipAssembler.kt:" + frames.get(move).getLineNumber();
                    moveCallSites.merge(callSite, 1, Integer::sum);
                }
                if (methods.contains(TILE_LIST)) {
                    tileListSamples++;
                    tileListTopFrames.merge(top, 1, Integer::sum);
                }
            }
        }
        if (assemblySamples == 0) {
            throw new IllegalStateException("No server stack includes " + ASSEMBLY);
        }
        System.out.println("serverExecutionSamples=" + serverSamples);
        System.out.println("truncatedServerStacks=" + truncatedStacks);
        System.out.println("nativeAssemblySamples=" + assemblySamples);
        System.out.println("samplesInRetrofitTileList=" + tileListSamples);
        printFrames("assemblyTop", assemblyTopFrames);
        printFrames("moveCallSite", moveCallSites);
        printFrames("tileListTop", tileListTopFrames);
    }

    private static String method(RecordedFrame frame) {
        var method = frame.getMethod();
        return method.getType().getName().replace('/', '.') + "." + method.getName();
    }

    private static void printFrames(String label, Map<String, Integer> frames) {
        frames.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(12)
                .forEach(frame -> System.out.printf("%s %d %s%n", label, frame.getValue(), frame.getKey()));
    }
}
