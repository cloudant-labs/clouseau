import json
import time
import requests
from locust import events, HttpUser, constant, task, tag

import data_partition as data
from logger import logger

URL = "http://adm:pass@localhost:5984"
DB = "http://adm:pass@localhost:5984/demo"
SESSION = requests.session()
timer = [time.perf_counter()]
DATASET = {'sensor-240': 5, 'sensor-260': 10, 'sensor-280': 15}


def create_database():
    if SESSION.get(DB).status_code == 200:
        SESSION.delete(DB)
    SESSION.put(DB + '?partitioned=true')


def insert_docs():
    for i in DATASET:
        payload = {"docs": []}
        with open(str(i) + ".json") as json_file:
            payload["docs"].extend(json.load(json_file))
        SESSION.post(DB + "/_bulk_docs", json=payload, headers={"Content-Type": "application/json"})


def create_indexes():
    design_docs = {
        "_id": "_design/all_sensors",
        "indexes": {
            "by_sensor": {
                "index": "function(doc) {"
                         "if(doc.sensor_id) {"
                         "index(\"sensor_id\", doc.sensor_id, {\"store\": true} );}}"
            }
        }
    }
    SESSION.put(f"{DB}/_design/all_sensors", data=json.dumps(design_docs))


def get_result(condition, response, func_name):
    response.success() if condition else response.failure(func_name + " FAILED.")


@events.test_start.add_listener
def _(environment, **kw):
    for i in DATASET:
        data.gen_data(DATASET[i], i)
    timer.append(time.perf_counter())
    logger.critical(f"1. Generate documents ---- TIME: {timer[-1] - timer[-2]}")


class LoadTest(HttpUser):
    host = URL
    wait_time = constant(1)

    def on_start(self):
        self.client.get("/", name=self.on_start.__name__)
        create_database()
        timer.append(time.perf_counter())
        logger.debug(f"2. Create Database ---- TIME: {timer[-1] - timer[-2]}")
        insert_docs()
        timer.append(time.perf_counter())
        logger.info(f"2. Insert docs ---- TIME: {timer[-1] - timer[-2]}")
        create_indexes()
        timer.append(time.perf_counter())
        logger.info(f"2. Create design docs ---- TIME: {timer[-1] - timer[-2]}")
        logger.critical("3. Start testing ... ")

    @tag('get')
    @task
    def get_partition(self):
        for i in DATASET:
            with self.client.get('/demo/_partition/' + i, catch_response=True, name='Get ' + i) as response:
                get_result(
                    response.json()['partition'] == i and response.json()['doc_count'] == DATASET[i],
                    response, self.get_partition.__name__)

    @tag("search")
    @task
    def search_all_docs(self):
        with self.client.get("/demo/_partition/sensor-260/_design/all_sensors/_search/by_sensor?query=*:*",
                             catch_response=True, name="Search All Docs") as response:
            get_result(
                response.status_code == 200,
                response, self.search_all_docs.__name__)

    def on_stop(self):
        self.client.get("/", name=self.on_stop.__name__)
        timer.append(time.perf_counter())
        logger.debug(f"4. Delete database, and shut down the locust ---- TIME: {timer[-1] - timer[-2]}")
        SESSION.delete(DB)
