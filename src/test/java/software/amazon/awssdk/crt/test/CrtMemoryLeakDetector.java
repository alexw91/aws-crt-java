package software.amazon.awssdk.crt.test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import org.junit.Assert;
import software.amazon.awssdk.crt.Log;

/**
 * Checks that the CRT doesn't have any major memory leaks. Probably won't detect very small leaks but will likely find
 * obvious large ones.
 */
public class CrtMemoryLeakDetector {
    // Allow up to 512 byte increase in memory usage between CRT Test Runs
    private final static int DEFAULT_ALLOWED_MEDIAN_MEMORY_BYTE_DELTA = 1024;
    private final static int DEFAULT_ALLOWED_MEDIAN_THREAD_COUNT_DELTA = 0;
    private final static int DEFAULT_NUM_LEAK_TEST_ITERATIONS = 20;

    private static long getEstimatedMemoryInUse() {

        Log.log(Log.LogLevel.Trace, Log.LogSubject.JavaCrtGeneral, "Checking Memory Usage");

        long estimatedMemInUse = Long.MAX_VALUE;

        for (int i = 0; i < 10; i++) {
            // Force a Java Garbage Collection before measuring to reduce noise in measurement
            System.gc();

            // Take the minimum of several measurements to reduce noise
            estimatedMemInUse = Long.min(estimatedMemInUse, (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        }

        Log.log(Log.LogLevel.Trace, Log.LogSubject.JavaCrtGeneral, String.format("MemUsage: %d", estimatedMemInUse));

        return estimatedMemInUse;
    }

    public static void leakCheck(Callable<Void> fn) throws Exception {
        leakCheck(DEFAULT_NUM_LEAK_TEST_ITERATIONS, DEFAULT_ALLOWED_MEDIAN_MEMORY_BYTE_DELTA, fn);
    }


    private static List<Long> getDeltaMeasurements(List<Long> measurementValues) {
        List<Long> deltas = new ArrayList<>();
        for (int i = 0; i < measurementValues.size() - 1; i++) {
            long prev = measurementValues.get(i);
            long curr = measurementValues.get(i + 1);
            long delta = (curr - prev);
            deltas.add(delta);
        }

        return deltas;
    }

    private static long getThreadCount() throws Exception {
        MBeanServerConnection connection = ManagementFactory.getPlatformMBeanServer();
        ObjectName threadMxBean = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
        Integer threadCount = (Integer)connection.getAttribute(threadMxBean, "ThreadCount");
        return threadCount;
    }

    public static void leakCheck(int numIterations, int maxMemoryLeakage, Callable<Void> fn) throws Exception {
        List<Long> memoryUsedMeasurements = new ArrayList<>();
        List<Long> threadCountMeasurements = new ArrayList<>();

        for (int i = 0; i < numIterations; i++) {
            fn.call();
            memoryUsedMeasurements.add(getEstimatedMemoryInUse());
            threadCountMeasurements.add(getThreadCount());
        }

        List<Long> memUseDeltas = getDeltaMeasurements(memoryUsedMeasurements);
        List<Long> threadCountDeltas = getDeltaMeasurements(threadCountMeasurements);

        // Sort from smallest to largest
        memUseDeltas.sort(null);
        threadCountDeltas.sort(null);

        // Get the median delta
        long p50MemoryUsageDelta = memUseDeltas.get(memUseDeltas.size() / 2);
        long p50ThreadCountDelta = threadCountDeltas.get(threadCountDeltas.size() / 2);

        if (p50MemoryUsageDelta > maxMemoryLeakage) {
            Assert.fail(String.format("Potential Memory Leak!\nMemory usage Deltas: %s\nMeasurements: %s\nDiff: %d", memUseDeltas.toString(), memoryUsedMeasurements.toString(), p50MemoryUsageDelta - maxMemoryLeakage) );
        }

        if (p50ThreadCountDelta > DEFAULT_ALLOWED_MEDIAN_THREAD_COUNT_DELTA) {
            Assert.fail(String.format("Potential Thread Leak!\nThread Count Deltas: %s\nMeasurements: %s", threadCountDeltas.toString(), threadCountDeltas.toString()));
        }
    }
}
