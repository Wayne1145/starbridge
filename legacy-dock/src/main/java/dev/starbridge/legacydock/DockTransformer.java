package dev.starbridge.legacydock;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;

/** Expands the original powered scan only when the adjacent monitoring station upgrades the machine. */
public final class DockTransformer implements IClassTransformer {
    private static final String TILE="zmaster587.advancedRocketry.tile.TileRocketBuilder";
    private static final String STORAGE="zmaster587.advancedRocketry.util.StorageChunk";
    private static final String HOOK="dev/starbridge/legacydock/DockBridge";
    private static final String BUF="Lio/netty/buffer/ByteBuf;";
    @Override public byte[] transform(String raw,String name,byte[] input) {
        if (input==null||(!TILE.equals(name)&&!STORAGE.equals(name))) return input;
        boolean tile=TILE.equals(name);
        ClassReader reader=new ClassReader(input);
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        final int[] changed=new int[3];
        reader.accept(new ClassVisitor(Opcodes.ASM5,writer) {
            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                MethodVisitor next=super.visitMethod(access,method,descriptor,signature,exceptions);
                if(tile && method.equals("getRocketPadBounds") && descriptor.endsWith("III)Lnet/minecraft/util/AxisAlignedBB;")) {
                    return new MethodVisitor(Opcodes.ASM5,next) {
                        @Override public void visitIntInsn(int operation,int value) {
                            if(operation==Opcodes.BIPUSH && value==16) {
                                super.visitVarInsn(Opcodes.ALOAD,1);
                                for(int v=2;v<=4;v++)super.visitVarInsn(Opcodes.ILOAD,v);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOK,"scanLimit","(Ljava/lang/Object;III)I",false);
                                changed[0]++;
                            } else super.visitIntInsn(operation,value);
                        }
                    };
                }
                if(!tile && descriptor.equals("("+BUF+")V") && (method.equals("readFromNetwork")||method.equals("writeToNetwork"))) {
                    boolean read=method.equals("readFromNetwork");
                    return new MethodVisitor(Opcodes.ASM5,next) {
                        @Override public void visitMethodInsn(int op,String owner,String called,String desc,boolean itf) {
                            if(read && op==Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/network/PacketBuffer") && called.equals("readByte") && desc.equals("()B")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOK,"readAxis","("+BUF+")I",false);changed[1]++;
                            } else if(!read && op==Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/network/PacketBuffer") && called.equals("writeByte") && desc.equals("(I)"+BUF)) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOK,"writeAxis","("+BUF+"I)"+BUF,false);changed[2]++;
                            } else super.visitMethodInsn(op,owner,called,desc,itf);
                        }
                        @Override public void visitFieldInsn(int op,String owner,String field,String desc) {
                            super.visitFieldInsn(op,owner,field,desc);
                            if(read&&op==Opcodes.PUTFIELD&&field.equals("sizeZ")&&desc.equals("I")) {
                                for(String axis:new String[]{"sizeX","sizeY","sizeZ"}) { super.visitVarInsn(Opcodes.ALOAD,0);super.visitFieldInsn(Opcodes.GETFIELD,owner,axis,"I"); }
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOK,"checkSize","(III)V",false);
                            }
                        }
                    };
                }
                return next;
            }
        },0);
        if(tile ? changed[0]!=6 : changed[1]!=3||changed[2]!=3) throw new IllegalStateException("Unsupported Advanced Rocketry release for Starbridge dock: "+name);
        return writer.toByteArray();
    }
}
