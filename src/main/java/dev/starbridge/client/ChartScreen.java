package dev.starbridge.client;

import dev.starbridge.client.survey.SurveyButton;
import dev.starbridge.compat.galacticraft.CelestialPort;
import dev.starbridge.voyage.VoyageNetwork;
import dev.starbridge.voyage.Voyages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** A paged chart for live upstream bodies. An unavailable body remains inspectable for its next step. */
public final class ChartScreen extends Screen {
    private static final int INK=0xff0c1723, PANEL=0xff142837, EDGE=0xff365566, TYPE=0xffdbe9e9, FAINT=0xff99afbb, AQUA=0xff74dec7;
    private static final int[] PLANETS={0xff69a9cc,0xffc4c7bb,0xffd68b64,0xffaea188,0xff9dbb90,0xffa798c7};
    private static final int PAGE_SIZE=6;
    private VoyageNetwork.Chart data;
    private int page, selected, left,top,w,h,mapW;
    private boolean controls, manualPairing;
    private int refreshTicks;
    private Button manual,cruise,lunarKit,technology;
    private EditBox computer;
    private ChartScreen(VoyageNetwork.Chart data) { super(tr("title")); this.data=data; selected=data.ports().size()>1?1:0; }
    private static Component tr(String key,Object... args) { return Component.translatable("nav.starbridge."+key,args); }
    public static void open(VoyageNetwork.Chart chart) {
        var mc=Minecraft.getInstance();
        if (mc.screen instanceof NavigationProofScreen proof && proof.update(chart)) return;
        if (mc.screen instanceof LunarKitScreen kit && kit.updateChart(chart)) return;
        if (mc.screen instanceof ChartScreen screen && screen.hasCore(chart)) { screen.updateChart(chart); screen.rebuildWidgets(); }
        else if (chart.opensScreen()) mc.setScreen(new ChartScreen(chart));
    }
    boolean hasCore(VoyageNetwork.Chart chart) {
        return data.core().equals(chart.core());
    }
    void updateChart(VoyageNetwork.Chart chart) {
        String target = selected >= 0 && selected < data.ports().size() ? data.ports().get(selected).id() : "";
        data = chart;
        for (int i = 0; i < data.ports().size(); i++) if (data.ports().get(i).id().equals(target)) { selected = i; break; }
    }
    static Component display(VoyageNetwork.Row p) {
        return CelestialPort.CLASSIC.contains(p.id())?Component.translatable("route.starbridge."+p.id()):Component.literal(p.title());
    }
    private Button button(Component text,int x,int y,int width,int height,Button.OnPress action) {
        return addRenderableWidget(Button.builder(text,action).bounds(x,y,width,height).build());
    }
    @Override protected void init() {
        w=Math.min(458,width-12); h=Math.min(225,height-8); left=(width-w)/2; top=(height-h)/2;
        mapW=Math.max(110,w*54/100); int right=left+w-7;
        int start = left + 8, tabWidth = (right - 21 - start - 12) / 4, pitch = tabWidth + 4;
        button(tr("star_map"),start,top+7,tabWidth,18,b->{controls=false;rebuildWidgets();}).active=controls;
        button(tr("systems"),start+pitch,top+7,tabWidth,18,b->{controls=true;rebuildWidgets();send(VoyageNetwork.Action.DEVICES,"",0);}).active=!controls;
        button(Component.translatable("slate.starbridge.guide.title"),start+2*pitch,top+7,tabWidth,18,b->FirstFlightScreen.open(this));
        addRenderableWidget(new SurveyButton(start + 3 * pitch, top + 7, tabWidth, 18));
        button(Component.literal("×"),right-17,top+7,17,18,b->onClose()).setTooltip(Tooltip.create(Component.translatable("gui.close")));
        if(controls) { initSystems(); return; }
        if(data.ports().isEmpty()) return;
        selected=Math.max(0,Math.min(selected,data.ports().size()-1));
        page=selected/PAGE_SIZE;
        for(int i=0;i<PAGE_SIZE && page*PAGE_SIZE+i<data.ports().size();i++) {
            int index=page*PAGE_SIZE+i;
            var row=data.ports().get(index);
            var marker=addRenderableWidget(new Button(px(i)-11,py(i)-11,22,22,display(row),b->{selected=index; refreshActions();},supplier -> supplier.get()) {
                @Override protected void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
                    if (isHoveredOrFocused()) graphics.renderOutline(getX(),getY(),width,height,EDGE);
                }
            });
            marker.setTooltip(Tooltip.create(display(row)));
            marker.setMessage(display(row));
        }
        int pageY=top+h-61;
        button(Component.literal("‹"),left+10,pageY,22,18,b->page(page-1)).active=page>0;
        button(Component.literal("›"),left+mapW-32,pageY,22,18,b->page(page+1)).active=(page+1)*PAGE_SIZE<data.ports().size();
        int x=left+8, bw=(w-20)/2;
        manual=button(tr("manual"),x,top+h-25,bw,19,b->fly(false));
        cruise=button(tr("cruise"),x+bw+4,top+h-25,w-bw-20,19,b->fly(true));
        lunarKit=button(tr("kit.title"),left+mapW+9,pageY,Math.min(120,w-mapW-18),18,
                b -> minecraft.setScreen(new LunarKitScreen(this)));
        technology=button(tr("proof.title"),left+mapW+9,pageY,Math.min(140,w-mapW-18),18,
                b -> minecraft.setScreen(new NavigationProofScreen(this, data, data.ports().get(selected))));
        refreshActions();
    }
    private void initSystems() {
        int x = left + 11, inner = w - 22, half = (inner - 5) / 2;
        button(tr("surface"), x, top + 36, half, 20, b -> send(VoyageNetwork.Action.SURFACE, "", 0)).active = !settling();
        button(tr(data.agent() ? "agent_off" : "agent_on"), x + half + 5, top + 36, inner - half - 5, 20,
                b -> send(VoyageNetwork.Action.AGENT, "", data.agent() ? 0 : 1));
        var devices = data.devices();
        if (manualPairing) {
            computer = addRenderableWidget(new EditBox(font, x + 2, top + 85, half - 4, 20, tr("computer")));
            computer.setHint(tr("computer"));
            computer.setMaxLength(10);
            computer.setFilter(value -> value.chars().allMatch(c -> c >= '0' && c <= '9'));
            var pair = button(tr("pair"), x + half + 5, top + 85, inner - half - 5, 20,
                    b -> send(VoyageNetwork.Action.PAIR, "", Integer.parseInt(computer.getValue())));
            pair.active = false;
            computer.setResponder(value -> {
                try { Integer.parseInt(value); pair.active = true; }
                catch (NumberFormatException invalid) { pair.active = false; }
            });
        } else {
            int cardWidth = (inner - 8) / dev.starbridge.compat.computer.AttachedComputers.PAGE_SIZE;
            int i = 0;
            for (int id : devices.ids()) {
                boolean paired = data.computer() == id;
                var card = addRenderableWidget(new ComputerCard(x + i++ * (cardWidth + 4), top + 82, cardWidth,
                        tr(paired ? "devices.current" : "devices.pair", id), paired, b -> send(VoyageNetwork.Action.PAIR_CONNECTED, "", id)));
                card.active = !paired;
            }
        }
        int row = top + 118;
        button(Component.literal("‹"), x, row, 21, 18, b -> send(VoyageNetwork.Action.DEVICES, "", devices.index() - 1)).active = !manualPairing && devices.index() > 0;
        button(Component.literal("›"), x + 24, row, 21, 18, b -> send(VoyageNetwork.Action.DEVICES, "", devices.index() + 1)).active = !manualPairing && devices.index() + 1 < devices.count();
        int column = (inner - 55) / 3;
        button(tr("devices.refresh"), x + 49, row, column, 18, b -> send(VoyageNetwork.Action.DEVICES, "", devices.index())).active = !manualPairing;
        button(tr(manualPairing ? "devices.back" : "devices.by_id"), x + 52 + column, row, column, 18, b -> {
            manualPairing = !manualPairing;
            rebuildWidgets();
            if (!manualPairing) send(VoyageNetwork.Action.DEVICES, "", devices.index());
        });
        button(tr("unpair"), x + 55 + 2 * column, row, inner - 55 - 2 * column, 18,
                b -> send(VoyageNetwork.Action.PAIR, "", -1)).active = data.computer() >= 0;
    }

    private void drawSystems(GuiGraphics graphics) {
        int x = left + 12;
        drawText(graphics, tr(manualPairing ? "computer" : "devices.title"), x, top + 64, w - 50, TYPE);
        if (!manualPairing && data.devices().count() > 1) {
            String pageNumber = (data.devices().index() + 1) + " / " + data.devices().count();
            graphics.drawString(font, pageNumber, left + w - 12 - font.width(pageNumber), top + 64, FAINT, false);
        }
        if (!manualPairing && data.devices().ids().isEmpty()) {
            var message = tr(net.minecraftforge.fml.ModList.get().isLoaded("computercraft") ? "devices.empty" : "devices.missing");
            graphics.drawWordWrap(font, message, x, top + 85, w - 24, FAINT);
        }
        drawText(graphics, tr("paired", data.computer() < 0 ? "—" : "#" + data.computer()), x, top + 148, w - 24, FAINT);
    }
    private int px(int i) { int[] x={27,61,76,37,72,17}; return left+8+(mapW-16)*x[i]/100; }
    private int py(int i) { int[] y={50,28,59,80,88,14}; return top+37+Math.max(40,h-120)*y[i]/100; }
    private void page(int newPage) { selected=Math.max(0,Math.min(newPage*PAGE_SIZE,data.ports().size()-1)); rebuildWidgets(); }
    private void refreshActions() {
        var destination = data.ports().get(selected);
        manual.active = cruise.active = destination.state() == Voyages.Availability.READY;
        technology.visible = destination.tier() > data.tier() && destination.state() != Voyages.Availability.MISSING_WORLD;
        lunarKit.visible = !technology.visible && destination.id().equals("moon") && CelestialPort.GC.equals(destination.provider());
    }
    private void send(VoyageNetwork.Action action,String key,int number){VoyageNetwork.action(data.core(),action,key,number);}
    private void fly(boolean auto){send(auto?VoyageNetwork.Action.CRUISE:VoyageNetwork.Action.MANUAL,data.ports().get(selected).id(),0);}
    private void drawText(GuiGraphics g,Component text,int x,int y,int space,int color) {
        g.drawString(font,font.plainSubstrByWidth(text.getString(),Math.max(0,space)),x,y,color,false);
    }
    private static void line(GuiGraphics g,int x,int y,int ex,int ey,int color) {
        int steps=Math.max(Math.abs(ex-x),Math.abs(ey-y));
        for(int i=1;i<steps;i+=2) {int xx=x+(ex-x)*i/steps,yy=y+(ey-y)*i/steps;g.fill(xx,yy,xx+1,yy+1,color);}
    }
    private void drawMap(GuiGraphics g) {
        int base=page*PAGE_SIZE,end=Math.min(base+PAGE_SIZE,data.ports().size());
        for(int i=0;i<68;i++){int sx=left+7+Math.floorMod(i*137+11,mapW-12),sy=top+32+Math.floorMod(i*71+13,Math.max(20,h-100));g.fill(sx,sy,sx+1,sy+1,(i%5==0)?0xffa0b8bc:0xff425f71);}
        for(int i=1;base+i<end;i++)line(g,px(0),py(0),px(i),py(i),EDGE);
        for(int i=0;base+i<end;i++){
            var row=data.ports().get(base+i); int x=px(i),y=py(i),r=i==0?5:4,color=PLANETS[i];
            if (selected==base+i)g.renderOutline(x-r-3,y-r-3,2*r+7,2*r+7,AQUA);
            g.fill(x-r+1,y-r,x+r,y+r+1,color);g.fill(x-r,y-r+1,x+r+1,y+r,color);g.fill(x-r,y+1,x-r+2,y+r-1,0x69344251);
            if(row.state()==Voyages.Availability.MISSING_WORLD)g.renderOutline(x-r,y-r,2*r+1,2*r+1,0xff627582);
            if (base+i==selected) {
                String label=font.plainSubstrByWidth(display(row).getString(),mapW-14);
                int labelX=Math.max(left+6,Math.min(left+mapW-font.width(label)-4,x-font.width(label)/2));
                g.drawString(font,label,labelX,y+r+5,TYPE,false);
            }
        }
        g.drawCenteredString(font,(page+1)+" / "+Math.max(1,(data.ports().size()+PAGE_SIZE-1)/PAGE_SIZE),left+mapW/2,top+h-55,FAINT);
        if(data.ports().isEmpty()) return;
        var row=data.ports().get(selected);int x=left+mapW+9,area=w-mapW-17;
        drawText(g,display(row),x,top+37,area,TYPE);drawText(g,Component.literal(row.provider()),x,top+51,area,AQUA);
        g.hLine(x,x+area-1,top+66,EDGE);
        drawText(g,tr("tier",row.tier(),data.tier()),x,top+74,area,TYPE);
        drawText(g,tr("start_energy",row.launchEnergy()),x,top+88,area,FAINT);
        drawText(g,tr("reserve",row.reserve()),x,top+101,area,FAINT);
        int y=top+119;
        Component reason=row.state()==Voyages.Availability.TECHNOLOGY ? tr("proof.gate")
                : tr("state."+row.state().name().toLowerCase(Locale.ROOT));
        int reasonBottom = lunarKit.visible || technology.visible ? lunarKit.getY() - font.lineHeight : top + h - 49;
        for(var l:font.split(reason,area)){if(y>reasonBottom)break;g.drawString(font,l,x,y,row.state()==Voyages.Availability.READY?AQUA:0xffffc683);y+=10;}
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float dt) {
        renderBackground(g);g.fill(left,top,left+w,top+h,INK);g.renderOutline(left,top,w,h,EDGE);g.hLine(left+1,left+w-2,top+29,EDGE);
        if(controls){drawSystems(g);}
        else {g.fill(left+mapW,top+30,left+w-1,top+h-41,PANEL);drawMap(g);}
        var feedback=data.notice().getString().isBlank()?tr("energy",data.energy()):data.notice();
        drawText(g,feedback,left+11,top+h-37,w-22,TYPE);
        super.render(g,mouseX,mouseY,dt);
    }
    private boolean settling() {
        return data.ports().stream().anyMatch(row -> row.state() == Voyages.Availability.SETTLING);
    }
    @Override public void tick() {
        super.tick();
        if (settling() && ++refreshTicks >= 20) {
            refreshTicks = 0;
            send(VoyageNetwork.Action.REFRESH, "", 0);
        }
    }
    @Override public boolean isPauseScreen(){return false;}
}
