package org.query.calc;

import it.unimi.dsi.fastutil.io.BinIO;
import org.query.executor.DataAggregator;
import org.query.executor.DataJoiner;
import org.query.executor.FileParser;
import org.query.model.FileContent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class QueryCalcImpl implements QueryCalc {

    /**
     * SELECT a, SUM(X * y * z) AS s FROM t1 LEFT JOIN (SELECT * FROM t2 JOIN t3) AS t ON a < b + c
     * GROUP BY a STABLE ORDER BY s DESC LIMIT 10;
     *
     * In order to achieve this result, solution will be done step by step,
     * Step 1: Input Parser: Parallelly read the all the files t1 , t2 and t3
     * Step 2: Data Joiner: Parallelly perform Data joiner operation to perform T1 LEFT JOIN (SELECT * FROM t2 JOIN t3)
     * Step 3: Data Aggregator: Parallelly perform Data aggregation to perform SUM(X * y * z)
     * Step 4: Data Sorter/Limiter : Sort and limit data based on Group by and Order by criteria
     * Step 5: Output Writer: Generate the output, convert into bytes and write into file.
     *
     * Design consideration:
     * 1. Computation time: Multi-Threading is used in almost all crucial phases of the solution
     * 2. Memory usage: Complete solution is designed with minimum and optimized extra space. Even for join operation,
     *                  new data structure is not created.
     * 3. Resource utilization : Resetting the  null reference for the resources which completed processing
     *
     * @param  t1 Path of Table t1 which has a and x as columns
     * @param  t2 Path of Table t2 which has b and y as columns
     * @param  t3 Path of Table t3 which has c and z as columns
     * @param  output Path of Result table which has a, SUM(x*y*z)
     * @return     void
     */
    @Override
    public void select(Path t1, Path t2, Path t3, Path output) throws IOException {

        List<FileParser> processorList = new ArrayList<>();

        processorList.add(new FileParser(t2));
        processorList.add(new FileParser(t3));
        ExecutorService fileParsingExecutor = Executors.newFixedThreadPool(3);
        try {

            //Step 1: Input parsing
            List<Future<Double[][]>> outputs = fileParsingExecutor.invokeAll(processorList);
            Future<Double[][]> t1Result = fileParsingExecutor.submit(new FileParser(t1));
            fileParsingExecutor.shutdown();
            fileParsingExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            //Step 2: Data Joiner
            Double[][] t1Data = t1Result.get();
            Double[][] t2Data = outputs.get(0).get();
            Double[][] t3Data = outputs.get(1).get();
            Map<Double,List<Double[]>> aggregatedData = new ConcurrentHashMap<>();
            Map<Double,Integer> indexes = new ConcurrentHashMap<>();
            ExecutorService joinerExecutor = Executors.newFixedThreadPool(5);
            AtomicInteger index = new AtomicInteger(-1);
            ReentrantLock lock = new ReentrantLock();
            for(int t1Index= 0;  t1Index < t1Data.length; t1Index++ )
                joinerExecutor.submit(new DataJoiner(t2Data,t3Data,aggregatedData,indexes,index,t1Data[t1Index][0],t1Data[t1Index][1],lock));
            t1Data = null;
            t2Data = null;
            t3Data = null;

            joinerExecutor.shutdown();
            joinerExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            //Step 3: Data Aggregator
            List<FileContent> contents = new CopyOnWriteArrayList<>();
            ExecutorService aggregationExecutor = Executors.newFixedThreadPool(4);
            for(Map.Entry<Double, List<Double[]>> dataIndex : aggregatedData.entrySet())
                aggregationExecutor.submit(new DataAggregator(indexes.get(dataIndex.getKey()), dataIndex.getValue(),contents));

            aggregationExecutor.shutdown();
            aggregationExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            aggregatedData = null;
            //Step 4: Data Sorter/Limiter
            Collections.sort(contents);
            contents = contents.stream().limit(10).collect(Collectors.toList());

            //Step 5: Output writer
            StringBuffer expectedOutput  = new StringBuffer();
            expectedOutput.append(contents.size());
            expectedOutput.append("\n");
            for(FileContent f : contents){
                expectedOutput.append(String.format("%.6f", Math.round(f.getData().left() * 1000000.0) / 1000000.0));
                expectedOutput.append(" ");
                expectedOutput.append(String.format("%.6f", Math.round(f.getData().right() * 1000000.0) / 1000000.0));
                expectedOutput.append("\n");
            }
            contents = null;
            BinIO.storeBytes(expectedOutput.toString().getBytes(),output.toFile());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }catch(ExecutionException exceptionExp){
            exceptionExp.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }catch(Exception e){
            e.printStackTrace();
        }

    }
}
