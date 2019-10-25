package software.amazon.awssdk.crt.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.Assert;

import java.lang.management.*;
import javax.management.*;


/**
 * Checks that the CRT doesn't have any major memory leaks. Probably won't detect very small leaks but will likely find
 * obvious large ones.
 */
public class CrtMemoryLeakDetector {
    // Allow up to 512 byte increase in memory usage between CRT Test Runs
    private final static int DEFAULT_ALLOWED_MEDIAN_MEMORY_BYTE_DELTA = 512;
    private final static int DEFAULT_NUM_LEAK_TEST_ITERATIONS = 20;

    private static long getEstimatedMemoryInUse() {
        long estimatedMemInUse = Long.MAX_VALUE;

        for (int i = 0; i < 10; i++) {
            // Force a Java Garbage Collection before measuring to reduce noise in measurement
            System.gc();

            // Take the minimum of several measurements to reduce noise
            estimatedMemInUse = Long.min(estimatedMemInUse, (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        }

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

    private static long getNumThreads() throws Exception {
        MBeanServerConnection connection = ManagementFactory.getPlatformMBeanServer();
        ObjectName threadMxBean = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
        Integer threadCount = (Integer)connection.getAttribute(threadMxBean, "ThreadCount");
        Integer deamonThreadCount = (Integer)connection.getAttribute(threadMxBean, "DaemonThreadCount");
        System.out.println("MxBean.ThreadCount: " + threadCount);
        System.out.println("MxBean.DaemonThreadCount " + deamonThreadCount);
        System.out.println("Thread.activeCount: " + Thread.activeCount());
        System.out.println("Thread.getAllStackTraces.size: " + Thread.getAllStackTraces().size());
        return threadCount;
    }

    public static void leakCheck(int numIterations, int maxLeakage, Callable<Void> fn) throws Exception {
        List<Long> memoryUsedMeasurements = new ArrayList<>();
        List<Long> threadUsedMeasurements = new ArrayList<>();

        for (int i = 0; i < numIterations; i++) {
            fn.call();
            memoryUsedMeasurements.add(getEstimatedMemoryInUse());
            threadUsedMeasurements.add(getNumThreads());
        }

        List<Long> memUseDeltas = getDeltaMeasurements(memoryUsedMeasurements);
        List<Long> threadUsedDeltas = getDeltaMeasurements(threadUsedMeasurements);

        System.out.println("Thread Use Deltas: " + threadUsedDeltas.toString());

        // Sort from smallest to largest
        memUseDeltas.sort(null);

        // Get the median delta
        long p50MemoryUsageDelta = memUseDeltas.get(memUseDeltas.size() / 2);

        if (p50MemoryUsageDelta > maxLeakage) {
            Assert.fail("Potential Memory Leak! Memory usage Deltas: " + memUseDeltas.toString());
        }
    }
}
