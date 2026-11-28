package dev.starbridge.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.registries.ForgeRegistries;

/** The canned potato variant from the original 1.7.10 Galacticraft pack loaded by Retrofit. */
public final class LunarRationsScreen extends Screen {
    private static final ResourceLocation BASIC = new ResourceLocation("galacticraftcore", "item.basicitem");
    private static final int POTATO = 18, TEXT = 0xffdeecec, ACCENT = 0xff76e0c7, MUTED = 0xffadc3ca;
    private final Screen previous;
    private final boolean galaxySpace;
    private CraftingRecipe recipe;
    private ItemStack ration = ItemStack.EMPTY;
    private int left, top, panelWidth, panelHeight;

    LunarRationsScreen(Screen previous, boolean galaxySpace) {
        super(label("title")); this.previous = previous; this.galaxySpace = galaxySpace;
    }
    private static Component label(String key, Object... args) {
        return Component.translatable("nav.starbridge.kit.rations." + key, args);
    }
    private boolean isRation(ItemStack stack) {
        return !stack.isEmpty() && BASIC.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))
                && stack.getTag() != null && stack.getTag().getInt("LegacyMeta") == POTATO;
    }
    @Override protected void init() {
        panelWidth=Math.min(338,width-8); panelHeight=Math.min(170,height-8);
        left=(width-panelWidth)/2; top=(height-panelHeight)/2;
        var level=minecraft.level;
        recipe=level == null ? null : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(candidate -> candidate.canCraftInDimensions(3,3) && isRation(candidate.getResultItem(level.registryAccess())))
                .findFirst().orElse(null);
        var item=ForgeRegistries.ITEMS.getValue(BASIC);
        if (recipe!=null) ration=recipe.getResultItem(level.registryAccess()).copy();
        else if(item!=null && item!=net.minecraft.world.item.Items.AIR) { ration=new ItemStack(item); ration.getOrCreateTag().putInt("LegacyMeta",POTATO); }
        addRenderableWidget(Button.builder(label("back"), b -> onClose()).bounds(left+12, top+panelHeight-24,panelWidth-24,18).build());
    }
    @Override public void onClose() { minecraft.setScreen(previous); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics g,int mx,int my,float dt) {
        renderBackground(g);
        g.fill(left,top,left+panelWidth,top+panelHeight,0xff142837);g.renderOutline(left,top,panelWidth,panelHeight,0xff426775);
        g.drawCenteredString(font,title,width/2,top+9,TEXT);
        int gridX=left+12,gridY=top+38,infoX=gridX+90;
        g.drawString(font,Component.translatable("block.minecraft.crafting_table"),gridX,gridY-12,MUTED,false);
        int columns=recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        ItemStack hover=ItemStack.EMPTY;
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            int x=gridX+col*21,y=gridY+row*21,index=row*columns+col;
            g.renderOutline(x,y,21,21,0xff486978);
            if(recipe==null || col>=columns || index>=recipe.getIngredients().size()) continue;
            var choices=recipe.getIngredients().get(index).getItems();
            if(choices.length==0) continue;
            var choice=choices[(int)((minecraft.level.getGameTime()/30)%choices.length)];
            g.renderItem(choice,x+3,y+3);
            if(mx>=x && mx<x+21 && my>=y && my<y+21) hover=choice;
        }
        g.drawCenteredString(font,"→",gridX+73,gridY+24,ACCENT);
        g.renderOutline(infoX,gridY+14,26,26,ACCENT);
        if(!ration.isEmpty()) {g.renderItem(ration,infoX+5,gridY+19);g.renderItemDecorations(font,ration,infoX+5,gridY+19);}
        if(mx>=infoX && mx<infoX+26 && my>=gridY+14 && my<gridY+40) hover=ration;
        int carried=0;
        if(minecraft.player!=null) {var inv=minecraft.player.getInventory();for(int slot=0;slot<inv.getContainerSize();slot++){var stack=inv.getItem(slot);if(isRation(stack)) carried+=stack.getCount();}}
        g.drawString(font,label("potatoes"),infoX+32,gridY+15,TEXT,false);
        g.drawString(font,label("carried",carried),infoX+32,gridY+30,carried>0?ACCENT:0xffffc683,false);
        Component hint=recipe==null ? label("no_recipe") : label(galaxySpace ? "outdoors" : "use");
        g.drawWordWrap(font,hint,gridX,gridY+70,panelWidth-24,MUTED);
        super.render(g,mx,my,dt);
        if(!hover.isEmpty()) g.renderTooltip(font,hover,mx,my);
    }
}
