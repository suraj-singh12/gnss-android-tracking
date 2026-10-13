package core

// Independent adversarial QA, not production code. Synthetic locations only.
// Test branch intentionally does not modify PR #29 or main.
import (
    "fmt"
    "math/rand"
    "sort"
    "sync"
    "testing"
    "time"
)

func adversarialFix(t *testing.T, deviceIndex, sessionIndex, n int) Message {
    t.Helper()
    seq := sessionIndex*10000 + n
    elapsed := float64(sessionIndex*2*24*3600 + deviceIndex*30 + n*5)
    x := float64(n)*4.1
    if (n/21)%2 == 1 { x = 4.1*float64(42-n%42) }
    y := float64(deviceIndex)*150 + float64(sessionIndex)*37
    accuracy := 1.5
    if n%29 == 0 { accuracy = 95 } // real-world deteriorated GNSS
    m := message(t,seq,elapsed,x,y,accuracy)
    m.Device = fmt.Sprintf("%08d-1111-4111-8111-111111111111",deviceIndex+1)
    m.ID = fmt.Sprintf("20000000-%04x-4000-8000-%012x",deviceIndex+1,seq)
    session := fmt.Sprintf("10000000-%04x-4000-8000-%012x",deviceIndex+1,sessionIndex+1)
    start := base.Add(time.Duration(sessionIndex*2*24*3600+deviceIndex*30)*time.Second)
    m.Observation = &ObservationIdentity{ID:m.ID,Session:session,SessionStart:start.Format(wireTime),Sequence:int64(n),ElapsedMillis:int64(n*5000)}
    return m
}
func sortedAdversarial(a []Candidate) []Candidate {
    out := append([]Candidate{}, a...)
    sort.Slice(out,func(i,j int)bool{return less(out[i].Message,out[j].Message,true)})
    return out
}

func TestAdversarialWeekMultiDeviceRandomArrivalAndRestart(t *testing.T) {
    for _, seed := range []int64{17,91,20261013} {
        t.Run(fmt.Sprintf("seed_%d",seed),func(t *testing.T){
            s,now,path := open(t)
            *now=base.Add(8*24*time.Hour)
            if e:=s.ActionMode("start","from_beginning");e!=nil{t.Fatal(e)}
            messages:=make([]Message,0,5*3*68)
            for deviceIndex:=0;deviceIndex<5;deviceIndex++{
                for sessionIndex:=0;sessionIndex<3;sessionIndex++{
                    for n:=1;n<=68;n++{messages=append(messages,adversarialFix(t,deviceIndex,sessionIndex,n))}
                }
            }
            rng:=rand.New(rand.NewSource(seed))
            // Atomic wire batches intentionally cannot mix devices or tracking sessions.
            // Shuffle legal per-source batches so histories, devices, and days
            // arrive out of order without violating Protocol v1.
            batches:=make([][]Message,0,100)
            for start:=0;start<len(messages);start+=68{
                group:=messages[start:start+68]
                for offset:=0;offset<len(group);{
                    width:=1+rng.Intn(14)
                    end:=offset+width
                    if end>len(group){end=len(group)}
                    batches=append(batches,append([]Message{},group[offset:end]...))
                    offset=end
                }
            }
            rng.Shuffle(len(batches),func(i,j int){batches[i],batches[j]=batches[j],batches[i]})
            seen:=make([]Candidate,0,len(messages))
            for batchIndex,group:=range batches{
                if _,e:=s.IngestBatch(batchBytes(t,group));e!=nil{t.Fatalf("seed %d batch %d: %v",seed,batchIndex,e)}
                for _,m:=range group{seen=append(seen,Candidate{m,now.Format(wireTime)})}
                if batchIndex%12==0{
                    st:=assertHistoryReference(t,s,sortedAdversarial(seen))
                    if st.ProjectionError!=""{t.Fatalf("projection error: %s",st.ProjectionError)}
                }
            }
            // Retry original packets; durable identity must make these no-ops.
            for k:=0;k<12;k++{
                group:=batches[rng.Intn(len(batches))]
                a,e:=s.IngestBatch(batchBytes(t,group))
                if e!=nil{t.Fatalf("retry %d: %v",k,e)}
                for _,ack:=range a.Acks {if ack.Result!="duplicate"{t.Fatalf("duplicate incorrectly counted as %q",ack.Result)}}
            }
            st:=assertHistoryReference(t,s,sortedAdversarial(seen))
            if len(st.Devices)!=5{t.Fatalf("missing devices: %d",len(st.Devices))}
            for _,d:=range st.Devices{
                r:=d.Reconstruction
                if r.Received!=204||r.Eligible!=204||r.Processed!=204||r.Projection!="completed"||len(d.HistorySessions)!=3 {
                    t.Fatalf("incomplete recovery: device=%s facts=%+v sessions=%d",d.ID,r,len(d.HistorySessions))
                }
                if r.Rejected==0{t.Fatal("quality rejections disappeared")}
            }
            if e:=s.Close();e!=nil{t.Fatal(e)}
            reloaded,e:=Open(path);if e!=nil{t.Fatal(e)}
            defer reloaded.Close()
            reloaded.Now=func()time.Time{return *now}
            assertHistoryReference(t,reloaded,sortedAdversarial(seen))
            t.Logf("PASS: %d retained fixes, five independent phones, fifteen source sessions, week-long age, interleaved arrival/duplicate/restart",len(seen))
        })
    }
}

func TestAdversarialConcurrentFivePhonesDashboardAndPolicyChanges(t *testing.T) {
    s,now,_:=open(t)
    *now=base.Add(8*24*time.Hour)
    if e:=s.ActionMode("start","from_beginning");e!=nil{t.Fatal(e)}
    var wg sync.WaitGroup
    failures:=make(chan error,500)
    sent:=make(chan Candidate,250)
    stopSnapshots:=make(chan struct{})
    wg.Add(1)
    go func(){
        defer wg.Done()
        for{
            select{
            case <-stopSnapshots:return
            default:
                st,e:=s.Snapshot(30)
                if e!=nil{failures<-fmt.Errorf("concurrent dashboard read: %w",e);return}
                if st.Recording==nil{failures<-fmt.Errorf("recording vanished");return}
                time.Sleep(5*time.Millisecond)
            }
        }
    }()
    for d:=0;d<5;d++{
        wg.Add(1)
        go func(idx int){
            defer wg.Done()
            for n:=1;n<=38;n++{
                m:=adversarialFix(t,idx,0,n)
                _,e:=s.Ingest(encode(t,m))
                if e!=nil{failures<-fmt.Errorf("device %d item %d: %w",idx,n,e);return}
                sent<-Candidate{m,now.Format(wireTime)}
            }
        }(d)
    }
    // Simulate operator modifying policy while phones continue uploading.
    wg.Add(1)
    go func(){
        defer wg.Done()
        for n:=0;n<3;n++{
            p:=DefaultPolicy()
            if n%2==0{p.Accuracy=20}else{p.Accuracy=25}
            if e:=s.SetPolicy(p);e!=nil{failures<-fmt.Errorf("policy changed under load: %w",e);return}
        }
    }()
    // Wait for writers separately (not snapshots) via bounded completion;
    // snapshots terminate after all 5 writers and policy changer finish.
    // No time.Sleep-based success condition.
    // This second wait group only protects producers.
    // Producers are guaranteed to finish; closing stopSnapshots ends the reader.
    // Use sentinel count from sent, which is bounded and buffered.
    for i:=0;i<5*38;i++{
        select{
        case <-sent:
        case <-time.After(60*time.Second):
            close(stopSnapshots)
            wg.Wait()
            t.Fatal("a phone's ingestion stalled or lost a message")
        }
    }
    close(stopSnapshots)
    wg.Wait()
    close(failures)
    for e:=range failures {t.Error(e)}
    st:=snapshot(t,s)
    if st.ProjectionError!="" {t.Fatal(st.ProjectionError)}
    if len(st.Devices)!=5{t.Fatal("not all phones registered")}
    for _,d:=range st.Devices {
        if d.Reconstruction.Received!=38||d.Reconstruction.Eligible!=38||d.Reconstruction.Projection!="completed" {t.Fatalf("wrong per-phone facts for %s: %+v",d.ID,d.Reconstruction)}
    }
    t.Log("PASS: parallel ingestion, dashboard reads and repeated live quality-policy modifications")
}

func TestAdversarialRecordingBoundaryDelayedReceiptsAndNoPhantomDistance(t *testing.T) {
    s,now,_:=open(t)
    *now=base.Add(10*time.Hour)
    if e:=s.ActionMode("start","from_beginning");e!=nil{t.Fatal(e)}
    *now=base.Add(11*time.Hour)
    action(t,s,"stop")
    *now=base.Add(13*time.Hour)
    action(t,s,"resume")
    *now=base.Add(14*time.Hour)
    session:="10000000-7777-4000-8000-000000000001"
    earlier:=message(t,1,0,0,0,1)
    earlier.Observation=&ObservationIdentity{ID:earlier.ID,Session:session,SessionStart:base.Format(wireTime),Sequence:1,ElapsedMillis:1000}
    stopped:=message(t,2,12*3600,100,0,1)
    stopped.Observation=&ObservationIdentity{ID:stopped.ID,Session:session,SessionStart:base.Format(wireTime),Sequence:2,ElapsedMillis:2000}
    resumed:=message(t,3,13*3600+1,200,0,1)
    resumed.Observation=&ObservationIdentity{ID:resumed.ID,Session:session,SessionStart:base.Format(wireTime),Sequence:3,ElapsedMillis:3000}
    for _,m:=range []Message{resumed,stopped,earlier}{if _,e:=s.ingestRole(encode(t,m),*now,"history");e!=nil{t.Fatal(e)}}
    st:=snapshot(t,s)
    if len(st.Points)!=2{t.Fatalf("expected original and resumed sections, got %d",len(st.Points))}
    if st.Points[0].Segment==st.Points[1].Segment{t.Fatal("phantom connector across stopped window")}
    if st.Devices[device].Reconstruction.Excluded!=1{t.Fatal("receipt in stopped interval wasn't excluded")}
    t.Log("PASS: delayed receipts do not cross Stop/Resume, and don't inflate distance")
}
