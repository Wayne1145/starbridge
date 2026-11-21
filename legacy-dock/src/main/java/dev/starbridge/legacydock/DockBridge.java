package dev.starbridge.legacydock;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Kept free of game class references so the two Forge runtimes remain isolated. */
public final class DockBridge {
    public static final int MAX_LENGTH=300, MAX_HEIGHT=64, MAX_VOLUME=2025000;
    private DockBridge() {}
    private static volatile Field monitor;
    private static volatile Method blockAt;
    public static int scanLimit(Object world, int x, int y, int z) {
        if (world == null) return 16;
        try {
            if (blockAt==null) {
                ClassLoader loader=world.getClass().getClassLoader();
                Class<?> worldType=Class.forName("net.minecraft.world.World",false,loader);
                Method lookup;
                try { lookup=worldType.getMethod("func_147439_a",int.class,int.class,int.class); }
                catch (NoSuchMethodException development) { lookup=worldType.getMethod("getBlock",int.class,int.class,int.class); }
                monitor=Class.forName("zmaster587.advancedRocketry.api.AdvancedRocketryBlocks",false,loader).getField("blockMonitoringStation");
                blockAt=lookup;
            }
            Object upgrade=monitor.get(null);
            return upgrade!=null && blockAt.invoke(world,x,y+1,z)==upgrade ? MAX_LENGTH : 16;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect the Advanced Rocketry monitoring station",e); }
    }
    /** Original packets for small rockets retain the one-byte layout. Each longer axis uses an extension marker. */
    public static ByteBuf writeAxis(ByteBuf out,int length) {
        if(length<1||length>MAX_LENGTH) throw new IllegalArgumentException("Invalid rocket dimension");
        return length<=127 ? out.writeByte(length) : out.writeByte(0).writeShort(length);
    }
    public static int readAxis(ByteBuf in) {
        int value=in.readUnsignedByte();
        if(value==0) value=in.readUnsignedShort();
        if(value<1||value>MAX_LENGTH) throw new IllegalArgumentException("Invalid rocket dimension");
        return value;
    }
    public static void checkSize(int x,int y,int z) {
        if(x<1||x>MAX_LENGTH||y<1||y>MAX_HEIGHT||z<1||z>MAX_LENGTH||(long)x*y*z>MAX_VOLUME)
            throw new IllegalArgumentException("Rocket exceeds the orbital dock's working volume");
    }
}
