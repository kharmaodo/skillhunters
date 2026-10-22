"""Small reproducible synthetic CPU smoke qualification, not a recruitment relevance benchmark."""
import json
import os
import platform
from pathlib import Path
import resource
import statistics
import sys
import time

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'services/embedding-worker'))
from engine import Engine
texts=['développement logiciel','software development','gestion de projet','project management','orchestration de conteneurs','container orchestration','Java','JavaScript','Kubernetes','PostgreSQL','Kafka','Spring Boot']
start=time.perf_counter();engine=Engine(os.environ['MODEL_DIRECTORY']);load=time.perf_counter()-start
runs=[]
for _ in range(10):
    start=time.perf_counter();result=engine.encode(texts);runs.append(time.perf_counter()-start)
vectors=result['vectors']
pairs=[{'a':texts[a],'b':texts[b],'cosine':round(sum(x*y for x,y in zip(vectors[a],vectors[b])),6)} for a,b in [(0,1),(2,3),(4,5),(6,7),(6,11)]]
print(json.dumps({'synthetic':True,'model':result['model'],'python':platform.python_version(),'platform':platform.platform(),'cpu':platform.processor(),'logicalCpus':os.cpu_count(),'inferenceThreads':1,'samples':10,'labelsPerSample':len(texts),'loadSeconds':round(load,4),'batchMedianSeconds':round(statistics.median(runs),4),'batchMaxSeconds':round(max(runs),4),'maxRssKiB':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'pairs':pairs,'scope':'Smoke qualification only: no corpus-level recall, fairness or 10000-candidate latency claim.'},ensure_ascii=False,indent=2))
