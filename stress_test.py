#!/usr/bin/env python3

import json
import random
import requests
import threading

base_url = 'http://localhost:5984'
db_count = 10
index_count = 10
nthreads = 20

s = requests.Session()
s.auth = ('foo', 'bar')
s.headers = {'content-type': 'application/json'}

bulk_req_body = '{"docs":[' + ",".join(("{} "*10).split()) + ']}'

def make_index(i):
    return {
        'indexes': {
            'foo': {
                'index': f'function(doc) {{ index("foo", {i}); }}'
            }
        }
    }

def stress_loop():
    while True:
        i = random.randrange(db_count)

        if random.random() > 0.8:
            r = s.post(f'{base_url}/db{i}/_bulk_docs', data = bulk_req_body)
            r.raise_for_status()
        else:
            j = random.randrange(index_count)
            r = s.get(f'{base_url}/db{i}/_design/foo{j}/_search/foo?q=*:*')
            if r.status_code != 200:
                print(r.json())



if __name__ == '__main__':
    print("cleaning up")
    for i in range(db_count):
        s.delete(f'{base_url}/db{i}')

    print("creating dbs and ddocs")
    for i in range(db_count):
        r = s.put(f'{base_url}/db{i}?q=1')
        r.raise_for_status()
        # ddocs
        for j in range(index_count):
            r = s.put(f'{base_url}/db{i}/_design/foo{j}',
                data = json.dumps(make_index(j)))
            r.raise_for_status()

    print("starting stress test")
    for i in range(nthreads):
        threading.Thread(target=stress_loop).start()
