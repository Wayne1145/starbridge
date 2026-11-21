package dev.starbridge.legacydock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.jar.JarFile;
import org.objectweb.asm.*;

/** Run against the pinned original game mod. Exercise complete byte streams with multiple hulls and boundaries. */
public final class DockCompatibilityTest {
    private static void require(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
    private static void checkOriginalClass(JarFile ar,String className) throws Exception {
        String name=className.replace('.','/')+".class";
        byte[] original;
        try (InputStream in=ar.getInputStream(ar.getJarEntry(name))) {original=bytes(in);}
        byte[] changed=new DockTransformer().transform(className,className,original);
        require(!Arrays.equals(original,changed),className+" was unchanged");
        final int[] calls=new int[4];
        new ClassReader(changed).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] exc){return new MethodVisitor(Opcodes.ASM5){
                @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf){
                    if(!owner.equals("dev/starbridge/legacydock/DockBridge"))return;
                    if(name.equals("scanLimit"))calls[0]++;
                    if(name.equals("readAxis"))calls[1]++;
                    if(name.equals("writeAxis"))calls[2]++;
                    if(name.equals("checkSize"))calls[3]++;
                }
            };}
        },0);
        require(className.endsWith("StorageChunk")?Arrays.equals(calls,new int[]{0,3,3,1}):Arrays.equals(calls,new int[]{6,0,0,0}),"wrong calls: "+Arrays.toString(calls));
    }
    private static byte[] bytes(InputStream in)throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] part=new byte[8192];int count;
        while((count=in.read(part))!=-1)out.write(part,0,count);return out.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        try(JarFile ar=new JarFile(Paths.get(args[0]).toFile())) {
            checkOriginalClass(ar,"zmaster587.advancedRocketry.tile.TileRocketBuilder");checkOriginalClass(ar,"zmaster587.advancedRocketry.util.StorageChunk");
        }
        ByteBuf wire=Unpooled.buffer();int[] values={1,16,64,100,127,128,200,255,256,300};
        for(int size:values)DockBridge.writeAxis(wire,size);
        for(int size:values)require(DockBridge.readAxis(wire)==size,"lost ship axis "+size);
        require(wire.readableBytes()==0,"trailing data");
        DockBridge.checkSize(300,18,41);DockBridge.checkSize(35,12,200);
        try{DockBridge.checkSize(300,64,300);throw new AssertionError("excessive volume");}catch(IllegalArgumentException expected){}
        try{DockBridge.readAxis(Unpooled.wrappedBuffer(new byte[]{0,1,45}));throw new AssertionError("dimension exceeds dock");}catch(IllegalArgumentException expected){}
        System.out.println("Verified both original AR 1.4.0-32 classes and ten wire axis sizes from 1 through 300");
    }
}
