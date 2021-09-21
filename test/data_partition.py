import json
from random import *
from faker import Faker


def write_to_files(data, filename):
    with open(filename, 'w') as outfile:
        json.dump(data, outfile)


def gen_data(n=10, id='sensor-0'):
    data = []
    fake = Faker()

    for i in range(n):
        data.append({'_id': id + ':sensor-reading-' + fake.uuid4()})
        data[i]['sensor_id'] = id
        data[i]['location'] = [float(fake.latitude()), float(fake.longitude())]
        data[i]['field_name'] = fake.sentence(nb_words=5, variable_nb_words=False)
        data[i]['readings'] = []
        for j in range(randint(1, 5)):
            data[i]['readings'].append([fake.iso8601(), round(uniform(0.1, 0.12), 2)])

    write_to_files(data, id + '.json')
