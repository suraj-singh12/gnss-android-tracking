package core

import (
	"os/exec"
	"testing"
)

func TestDashboardDrawsLivePositionWithoutRecording(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("Node required for browser drawing smoke check")
	}
	if out, err := exec.Command(node, "--check", "../../web/app.js").CombinedOutput(); err != nil {
		t.Fatalf("syntax: %s %v", out, err)
	}
	script := `
 const fs=require('fs'), vm=require('vm'),assert=require('assert');
 class Element {constructor(){this.children=[];this.attrs={};this.clientWidth=800;this.clientHeight=600;} setAttribute(k,v){this.attrs[k]=v;} append(...x){for(const e of x)e.parent=this;this.children.push(...x);} remove(){this.parent.children=this.parent.children.filter(e=>e!==this);} querySelectorAll(){return this.children.filter(e=>["live","located-sos"].includes(e.attrs["data-layer"]));} replaceChildren(){this.children=[];}}
 const elements={tracks:new Element(),empty:new Element(),hover:new Element(),'all-observations':new Element(),'map-mode':{value:'blank'},'map-mode-label':new Element(),'projection-status':new Element(),'scale-label':new Element(),'track-layer-label':new Element(),'map-opacity':{value:'1'},'layer-hillshade':new Element(),'layer-contours':new Element(),'layer-elevation':new Element()};
 const device={device_id:'phone',current_position:true,live_x_m:1000,live_y_m:50,location:{fix:{latitude:28,longitude:77,observed_at:'2026-10-08T12:00:00Z',horizontal_accuracy_m:3}},snapshot:{party:{id:'Alpha'}}};
 const ctx={performance:require("node:perf_hooks").performance,uiTiming:()=>{},historyDrawKey:undefined,historyDrawMap:undefined,historyDrawTerrain:undefined,GeoMap:require('../../web/map.js'),viewport:undefined,offlineMap:undefined,terrain:undefined,locatedSOS:undefined,selectedParty:undefined,gnssLabel:()=> 'GNSS Fresh',document:{documentElement:{dataset:{appearance:'day'}},createElementNS:()=>new Element(),querySelectorAll:()=>[]},svgNS:'svg',$:id=>elements[id],state:{devices:{phone:device},points:[]},hidden:new Set(),color:()=> '#123456',dash:()=>'',time:s=>s,distance:s=>s};
 vm.createContext(ctx);
 const code=fs.readFileSync('../../web/app.js','utf8');vm.runInContext(code.slice(code.indexOf('function svg('),code.indexOf('let polling')),ctx);
 vm.runInContext('draw()',ctx);
 const live=()=>elements.tracks.children.find(e=>e.attrs['data-layer']==='live');
 assert.equal(elements.empty.hidden,true);assert.equal(live().children.filter(e=>e.attrs['data-live-device']).length,1);assert.equal(live().children[0].attrs['data-live-device'],'phone');
 // Historical geometry is independent and does not replace current marker.
 ctx.state.points=[{device_id:'phone',segment_id:'old',x_m:0,y_m:0,dot:false,fix:device.location.fix,cumulative_m:0}];
 vm.runInContext('draw()',ctx);assert.equal(live().children.filter(e=>e.attrs['data-live-device']).length,1);
 device.current_position=false;vm.runInContext('draw()',ctx);assert.equal(live().children.filter(e=>e.attrs['data-live-device']).length,0);device.location=null;
 ctx.state.points=[];vm.runInContext('draw()',ctx);assert.equal(elements.empty.hidden,false);
 `
	if out, err := exec.Command(node, "-e", script).CombinedOutput(); err != nil {
		t.Fatalf("live marker smoke: %s %v", out, err)
	}
}
