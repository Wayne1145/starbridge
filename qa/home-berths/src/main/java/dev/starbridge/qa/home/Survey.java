package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.voyage.Voyages;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.util.UUID;

/** Test access to the same production survey, packet and native berth planner used by the helm. */
final class Survey {
    final VsShipBackend.StorageBounds hull;
    private final Object job;
    private final Class<?> type;

    Survey(UUID id, int x, int z, VsShipBackend.StorageBounds bounds, ServerLevel level, Vec3 port, int tick)
            throws ReflectiveOperationException {
        hull = bounds;
        type = Class.forName("dev.starbridge.orbit.LandingSurvey");
        var constructor = type.getDeclaredConstructor(UUID.class,int.class,int.class,VsShipBackend.StorageBounds.class,
                Voyages.ApproachTarget.class,int.class);
        constructor.setAccessible(true);
        job = constructor.newInstance(id,x,z,bounds,new Voyages.ApproachTarget(level,port),tick);
    }

    private Method method(String name, Class<?>... types) throws ReflectiveOperationException {
        var method = type.getDeclaredMethod(name,types);
        method.setAccessible(true);
        return method;
    }

    void work() throws ReflectiveOperationException { method("work").invoke(job); }
    void reset(boolean confirmed) throws ReflectiveOperationException { method("reset",boolean.class).invoke(job,confirmed); }
    void release() throws ReflectiveOperationException { method("release").invoke(job); }
    Vec3 anchor() throws ReflectiveOperationException { return (Vec3)method("anchor").invoke(job); }
    OrbitWire.Site packet() throws ReflectiveOperationException { return (OrbitWire.Site)method("packet").invoke(job); }
    boolean finished() throws ReflectiveOperationException {
        var field=type.getDeclaredField("finished");
        field.setAccessible(true);
        return field.getBoolean(job);
    }

    static Vec3 place(ServerLevel source, ServerLevel home, long id, VsShipBackend.StorageBounds bounds,
                      Vec3 chosen, VsShipBackend vs) throws ReflectiveOperationException {
        var origin=vs.inspectById(source,id).orElseThrow().worldPosition();
        var find=Class.forName("dev.starbridge.transit.BerthPlanner").getDeclaredMethod("find",ServerLevel.class,
                ServerLevel.class,long.class,VsShipBackend.StorageBounds.class,Vec3.class,Vec3.class,boolean.class,VsShipBackend.class);
        find.setAccessible(true);
        Vec3 berth=(Vec3)find.invoke(null,source,home,id,bounds,new Vec3(origin.x(),origin.y(),origin.z()),chosen,true,vs);
        vs.transfer(source,home,id,new VsShipBackend.Vector(berth.x,berth.y,berth.z));
        return berth;
    }
}
