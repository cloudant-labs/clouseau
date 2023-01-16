import math
import json
import time
import requests
from locust import events, HttpUser, constant, task, tag

import data
from logger import logger

URL = "http://adm:pass@localhost:5984"
DB = "http://adm:pass@localhost:5984/demo"
SESSION = requests.session()
timer = [time.perf_counter()]


def create_database():
    if SESSION.get(DB).status_code == 200:
        SESSION.delete(DB)
    SESSION.put(DB)


def insert_docs(docs_number, files_number):
    for i in range(math.ceil(docs_number / files_number)):
        payload = {"docs": []}
        with open("data" + str(i) + ".json") as json_file:
            payload["docs"].extend(json.load(json_file))
        SESSION.post(DB + "/_bulk_docs", json=payload, headers={"Content-Type": "application/json"})


def create_indexes():
    design_docs = {
        "_id": "_design/search",
        "indexes": {
            "search_index": {
                "index": "function(doc) {if(doc.gender) {index(\"gender\", doc.gender, {\"store\": true} );};"
                         "if(doc.age) {index(\"age\", doc.age, {\"store\": true} );};"
                         "if(doc.married) {index(\"married\", doc.married, {\"store\": true} );};"
                         "if(doc.ethnicity) {index(\"ethnicity\", doc.ethnicity, {\"store\": true} );};"
                         "if(doc.ethnicity) {index(\"ethnicity\", doc.ethnicity, {\"store\": true} );}}"
            },
            "geo_index": {
                "index": "function(doc) {"
                         "if(doc.address.city) {"
                         "index(\"city\", doc.address.city, {\"store\": true} );"
                         "index(\"lat\", doc.lat, {\"store\": true} );"
                         "index(\"lon\", doc.lon, {\"store\": true} );}}"
            }
        }
    }
    SESSION.put(f"{DB}/_design/search", data=json.dumps(design_docs))


def get_result(condition, response, func_name):
    response.success() if condition else response.failure(func_name + " FAILED.")


@events.init_command_line_parser.add_listener
def _(parser):
    parser.add_argument("--docs-number", type=int, env_var="LOCUST_DOCS_NUMBER", default=100_000,
                        help="How many documents do you want to generate")
    parser.add_argument("--files-number", type=int, env_var="LOCUST_FILES_NUMBER", default=5000,
                        help="How many documents are stored in each JSON file")


@events.test_start.add_listener
def _(environment, **kw):
    data.gen_data(environment.parsed_options.docs_number, environment.parsed_options.files_number)
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
        insert_docs(self.environment.parsed_options.docs_number, self.environment.parsed_options.files_number)
        timer.append(time.perf_counter())
        logger.info(f"2. Insert docs ---- TIME: {timer[-1] - timer[-2]}")
        create_indexes()
        timer.append(time.perf_counter())
        logger.info(f"2. Create design docs ---- TIME: {timer[-1] - timer[-2]}")
        logger.critical("3. Start testing ... ")
        with open("analysis.json") as json_file:
            self.data = json.load(json_file)

    @tag("search")
    @task
    def search_all_docs(self):
        with self.client.get("/demo/_design/search/_search/search_index?query=*:*",
                             catch_response=True, name="Search All Docs") as response:
            get_result(
                response.status_code == 200 and response.json()["total_rows"] == self.data["total_rows"],
                response, self.search_all_docs.__name__)

    @tag("search")
    @task
    def search_gender_is_male(self):
        with self.client.get("/demo/_design/search/_search/search_index?query=gender:m",
                             catch_response=True, name="Search Gender is Male") as response:
            get_result(
                response.status_code == 200 and response.json()["total_rows"] == self.data["gender"]["M"],
                response, self.search_gender_is_male.__name__)

    @tag("search")
    @task
    def search_gender_is_male_with_limit_2(self):
        with self.client.get("/demo/_design/search/_search/search_index?query=gender:m&limit=2",
                             catch_response=True, name="Search Gender Male with Limit 2") as response:
            get_result(
                response.status_code == 200 and len(response.json()["rows"]) == 2,
                response, self.search_gender_is_male_with_limit_2.__name__)

    @tag("search")
    @task
    def search_gender_is_female_and_sort_by_age(self):
        with self.client.get("/demo/_design/search/_search/search_index?query=gender:f&sort=\"age\"",
                             catch_response=True, name="Search Gender is Female AND Sort by age") as response:
            result = response.json()
            if self.data["gender"]["F"] >= 2:
                conditions = result["total_rows"] == self.data["gender"]["F"] and \
                             result["rows"][0]["order"][0] <= result["rows"][1]["order"][0]
            else:
                conditions = result["total_rows"] == self.data["gender"]["F"]
            get_result(conditions, response, self.search_gender_is_female_and_sort_by_age.__name__)

    @tag("search")
    @task
    def search_married_people_age_should_greater_than_21(self):
        with self.client.get(
                "/demo/_design/search/_search/search_index?query=married:true",
                catch_response=True, name="Search married people age > 21") as response:
            result = response.json()
            for i in result["rows"]:
                if i["fields"]["age"] <= 21:
                    response.failure(self.search_married_people_age_should_greater_than_21.__name__)
            response.success()

    @tag("search")
    @task
    def search_ethnicity_white_or_asian(self):
        with self.client.get(
                "/demo/_design/search/_search/search_index?query=ethnicity:White OR ethnicity:Asian",
                catch_response=True, name="Search ethnicity White OR Asian") as response:
            result = response.json()
            get_result(
                response.status_code == 200 and
                result["total_rows"] == self.data["ethnicity"]["White"] + self.data["ethnicity"]["Asian"],
                response, self.search_ethnicity_white_or_asian.__name__)

    @tag("geo")
    @task
    def search_lat_within_range_0_to_50_include(self):
        with self.client.get(
                "/demo/_design/search/_search/geo_index?query=lat:[0+TO+50]",
                catch_response=True, name="Search latitude within [0, 50]") as response:
            result = response.json()
            get_result(
                response.status_code == 200 and
                result["total_rows"] == self.data["lat"],
                response, self.search_lat_within_range_0_to_50_include.__name__)

    @tag("geo")
    @task
    def search_lon_within_range_0_to_50_exclude(self):
        with self.client.get(
                "/demo/_design/search/_search/geo_index?query=lon:{0+TO+50}",
                catch_response=True, name="Search longitude within {0, 50}") as response:
            result = response.json()
            get_result(
                response.status_code == 200 and
                result["total_rows"] == self.data["lon"],
                response, self.search_lon_within_range_0_to_50_exclude.__name__)

    @tag("geo")
    @task
    def search_geo_within_range_0_to_50(self):
        with self.client.get(
                "/demo/_design/search/_search/geo_index?query=lat:[0+TO+50] AND lon:{0+TO+50}",
                catch_response=True, name="Search geo within [0, 50] (AND)") as response:
            result = response.json()
            get_result(
                response.status_code == 200 and
                result["total_rows"] == self.data["geo"],
                response, self.search_geo_within_range_0_to_50.__name__)

    def on_stop(self):
        self.client.get("/", name=self.on_stop.__name__)
        timer.append(time.perf_counter())
        logger.debug(f"4. Delete database, and shut down the locust ---- TIME: {timer[-1] - timer[-2]}")
        SESSION.delete(DB)
