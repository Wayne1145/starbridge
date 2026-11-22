package dev.starbridge.flightqa;

import dev.starbridge.client.survey.SurfaceSurveyScreen;
import net.minecraft.core.BlockPos;
import java.lang.reflect.Method;
import java.util.*;

/** Choose short walking legs from the sampled elevation cells already shown in the open notebook. */
public final class SurveyRoute {
    private static final int WIDTH=25, HALF=WIDTH/2, START=HALF*WIDTH+HALF;
    private record QueueEntry(int cell,double cost) {}

    public static Deque<BlockPos> across(SurfaceSurveyScreen screen, BlockPos finalGoal, Set<Long> blocked) throws Exception {
        Object terrain=field(screen,"terrain"); int stride=(int)field(screen,"stride");
        int cx=(int)field(screen,"centerX"),cz=(int)field(screen,"centerZ");
        Method sample=terrain.getClass().getDeclaredMethod("at",int.class,int.class);sample.setAccessible(true);
        int[] elevation=new int[WIDTH*WIDTH], parent=new int[elevation.length];
        for(int z=0;z<WIDTH;z++) for(int x=0;x<WIDTH;x++) elevation[z*WIDTH+x]=(int)sample.invoke(terrain,cx+x-HALF,cz+z-HALF);
        int startElevation=elevation[START]; if(startElevation==Integer.MIN_VALUE) return new ArrayDeque<>();
        Arrays.fill(parent,-1);double[] dist=new double[elevation.length];Arrays.fill(dist,Double.POSITIVE_INFINITY);dist[START]=0;
        var queue=new PriorityQueue<QueueEntry>(Comparator.comparingDouble(QueueEntry::cost));queue.add(new QueueEntry(START,0));
        int best=START;double score=Math.hypot(cx*stride-finalGoal.getX(),cz*stride-finalGoal.getZ());
        while(!queue.isEmpty()) {
            var curr=queue.remove();int from=curr.cell();if(curr.cost()>dist[from]) continue;
            int x=from%WIDTH,z=from/WIDTH;
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                if(dx==0&&dz==0)continue;
                int nx=x+dx,nz=z+dz;if(nx<0||nx>=WIDTH||nz<0||nz>=WIDTH)continue;
                int next=nz*WIDTH+nx;if(elevation[next]==Integer.MIN_VALUE)continue;
                int rise=elevation[next]-elevation[from];
                int maxRise=stride==1?1:3, maxFall=stride==1?1:4;
                if(rise>maxRise || rise < -maxFall) continue;
                if (stride==1 && dx!=0 && dz!=0) {
                    int sideX=elevation[z*WIDTH+nx],sideZ=elevation[nz*WIDTH+x];
                    if (sideX==Integer.MIN_VALUE || sideZ==Integer.MIN_VALUE || Math.abs(sideX-elevation[from])>1 || Math.abs(sideZ-elevation[from])>1) continue;
                }
                int wx=(cx+nx-HALF)*stride,wz=(cz+nz-HALF)*stride;
                if(blocked.contains(BlockPos.asLong(wx,0,wz)))continue;
                double travel=dist[from]+Math.hypot(dx,dz)*stride+Math.abs(rise)*1.6;
                if(travel>=dist[next])continue;
                dist[next]=travel;parent[next]=from;queue.add(new QueueEntry(next,travel));
                double remaining=Math.hypot(wx-finalGoal.getX(),wz-finalGoal.getZ())+.18*travel;
                if(remaining<score){score=remaining;best=next;}
            }
        }
        var path=new ArrayDeque<BlockPos>();
        for(int i=best;i!=START&&i>=0;i=parent[i]) path.addFirst(new BlockPos((cx+i%WIDTH-HALF)*stride,elevation[i],(cz+i/WIDTH-HALF)*stride));
        return path;
    }
    /** Hover a surveyed cell just ahead of the map centre using this test window's normal mouse callback. */
    static int hoverWalkingCell(net.minecraft.client.Minecraft game, SurfaceSurveyScreen screen) throws Exception {
        int cell = (int) field(screen, "cell");
        int x = (int) field(screen, "mapX") + 18 * cell + cell / 2;
        int y = (int) field(screen, "mapY") + 15 * cell + cell / 2;
        var window = game.getWindow();
        var handle = window.getWindow();
        var cursor = org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(handle, null);
        if (cursor == null) throw new IllegalStateException("The test window has no mouse callback");
        org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(handle, cursor);
        cursor.invoke(handle, (x + .5) * window.getScreenWidth() / screen.width,
                (y + .5) * window.getScreenHeight() / screen.height);
        Object terrain = field(screen, "terrain");
        var sample = terrain.getClass().getDeclaredMethod("at", int.class, int.class);
        sample.setAccessible(true);
        int height = (int) sample.invoke(terrain, (int) field(screen, "centerX") + 2, (int) field(screen, "centerZ") - 1);
        if (height == Integer.MIN_VALUE) throw new IllegalStateException("The selected map cell has not been surveyed");
        return height;
    }

    /** Pick two unobscured boundaries for comparison with actual framebuffer pixels after the run. */
    static Map<String, Object> screenshotBoundaries(net.minecraft.client.Minecraft game, SurfaceSurveyScreen screen) throws Exception {
        int cell = (int) field(screen, "cell"), left = (int) field(screen, "mapX"), top = (int) field(screen, "mapY");
        int cx = (int) field(screen, "centerX"), cz = (int) field(screen, "centerZ");
        Object terrain = field(screen, "terrain");
        var at = terrain.getClass().getDeclaredMethod("at", int.class, int.class);
        at.setAccessible(true);
        var readings = new LinkedHashMap<String, Object>();
        for (int row = 1; row < 31 && readings.size() < 2; row++) {
            if (row >= 9 && row <= 20) continue; // Keep clear of the hover tooltip and central player symbol.
            for (int col = 2; col < 30; col++) {
                int x = cx + col - 16, z = cz + row - 16;
                int a = (int) at.invoke(terrain, x, z), b = (int) at.invoke(terrain, x + 1, z);
                if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE) continue;
                String type = Math.abs(a - b) >= 2 ? "ledge" : "gentle";
                if (readings.containsKey(type)) continue;
                int px = left + (col + 1) * cell - 1, py = top + row * cell + cell / 2;
                readings.put(type, Map.of("heights", List.of(a, b), "pixel", List.of(
                        (int)((px + .5) * game.getWindow().getWidth() / screen.width),
                        (int)((py + .5) * game.getWindow().getHeight() / screen.height))));
            }
        }
        return readings;
    }

    private static Object field(Object object,String name) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
}
