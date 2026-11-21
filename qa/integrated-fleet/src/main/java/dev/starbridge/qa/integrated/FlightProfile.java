package dev.starbridge.qa.integrated;

import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="starbridgeintegratedqa",value=Dist.CLIENT)
public final class FlightProfile {
    public static final boolean ENABLED=Files.isRegularFile(Path.of("profile-flight.fixture"));
    static volatile Thread render,server;static volatile boolean stop;static Thread worker;
    static final ConcurrentMap<String,Counter> counters=new ConcurrentHashMap<>();
    static final ThreadLocal<Map<String,Long>> entered=ThreadLocal.withInitial(HashMap::new);
    static final class Counter { final LongAdder hits=new LongAdder(),nanos=new LongAdder();final LongAccumulator maximum=new LongAccumulator(Math::max,0); }
    static final Map<String,Object> oldModel=new HashMap<>();static Object lastScreen;static long previousStage;static String priorStage="";
    public static void enter(String what){if(ENABLED)entered.get().put(what,System.nanoTime());}
    public static void leave(String what){if(!ENABLED)return;var first=entered.get().remove(what);if(first!=null)count(what,System.nanoTime()-first);}
    static void count(String what,long ns){var c=counters.computeIfAbsent(Pilot.phase+":"+what,k->new Counter());c.hits.increment();c.nanos.add(ns);c.maximum.accumulate(ns);}
    static void begin(Thread thread){if(!ENABLED||worker!=null)return;render=thread;worker=new Thread(FlightProfile::sample,"Starbridge frame sampler");worker.setDaemon(true);worker.start();}
    static void sample(){var bean=ManagementFactory.getThreadMXBean();try(var file=Files.newBufferedWriter(Path.of("profile-stacks.tsv"))){file.write("ns\tphase\tthread\tcpuNs\tstate\tstack\n");int n=0;while(!stop){if(server!=null&&render!=null){long[] ids={render.getId(),server.getId()};var snap=bean.getThreadInfo(ids,52);String phase=Pilot.phase;long now=System.nanoTime();for(int i=0;i<snap.length;i++){var tr=snap[i];if(tr==null)continue;var msg=new StringBuilder();for(var frame:tr.getStackTrace())msg.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append('|');file.write(now+"\t"+phase+"\t"+tr.getThreadName()+"\t"+bean.getThreadCpuTime(ids[i])+"\t"+tr.getThreadState()+"\t"+msg+"\n");}if(++n%50==0)file.flush();}Thread.sleep(19);}}catch(Exception ex){IntegratedFlight.fail(ex);}}
    @SubscribeEvent public static void before(ScreenEvent.Render.Pre e){if(ENABLED&&e.getScreen().getClass().getSimpleName().equals("OrbitScreen"))enter("cockpitUI");}
    @SubscribeEvent public static void after(ScreenEvent.Render.Post e)throws Exception{if(!ENABLED||!e.getScreen().getClass().getSimpleName().equals("OrbitScreen"))return;leave("cockpitUI");var sc=e.getScreen();if(sc!=lastScreen){oldModel.clear();lastScreen=sc;count("newScreen",0);}
        for(String key:List.of("hulls","meshes")){var f=sc.getClass().getDeclaredField(key);f.setAccessible(true);for(var entry:((Map<?,?>)f.get(sc)).entrySet()){var k=key+entry.getKey();if(oldModel.put(k,entry.getValue())!=entry.getValue()){count("new-"+key,0);if(key.equals("hulls")){var voxels=(List<?>)entry.getValue().getClass().getMethod("voxels").invoke(entry.getValue());count("hullVoxels="+voxels.size(),0);}}}}
    }
    @SubscribeEvent public static void world(RenderLevelStageEvent e){if(!ENABLED)return;long now=System.nanoTime();var stage=e.getStage().toString();if(previousStage!=0)count(priorStage+">"+stage,now-previousStage);previousStage=now;priorStage=stage;}
    static void finish()throws Exception{if(!ENABLED)return;stop=true;if(worker!=null)worker.join(1500);var report=new TreeMap<String,Object>();counters.forEach((k,v)->report.put(k,Map.of("count",v.hits.sum(),"totalMs",v.nanos.sum()/1e6,"maxMs",v.maximum.get()/1e6)));IntegratedFlight.write("profile-counters.json",report);}
}
