import math
import json
from datetime import date
from random import choice
from faker import Faker


def write_to_files(data, filename, files_number=0):
    if files_number:
        chunks = [data[files_number * i:files_number * (i + 1)]
                  for i in range(math.ceil(len(data) / files_number))]
        idx = filename.find('.json')
        for i in range(len(chunks)):
            with open(filename[:idx] + str(i) + filename[idx:], 'w') as outfile:
                json.dump(chunks[i], outfile)
    else:
        with open(filename, 'w') as outfile:
            json.dump(data, outfile)


def gen_data(n=10, files_number=10, latmin=0, latmax=50, lonmin=0, lonmax=50):
    data = []
    counter = {}
    fake = Faker()
    fields = ['married', 'ethnicity', 'gender']
    counter['total_rows'] = n

    for i in range(n):
        data.append({'_id': str(i)})
        data[i]['gender'] = choice(['M', 'F'])
        data[i]['name'] = fake.name_male() if data[i]['gender'] == 'M' else fake.name_female()
        data[i]['date_of_birth'] = fake.iso8601()
        data[i]['age'] = date.today().year - int(data[i]['date_of_birth'][:4])
        data[i]['married'] = 'False' if data[i]['age'] < 22 else choice(['True', 'False'])
        data[i]['ethnicity'] = choice(['White', 'Black', 'Asian', 'Hispanic', 'non-Hispanic'])
        data[i]['address'] = {'full_address': fake.address()}
        data[i]['address']['city'] = data[i]['address']['full_address'][
                                     data[i]['address']['full_address'].find('\n') + 1: -10]
        data[i]['address']['area'] = data[i]['address']['full_address'][-8:-6]
        data[i]['address']['zip'] = data[i]['address']['full_address'][-5:]
        data[i]['lat'] = float(fake.latitude())
        data[i]['lon'] = float(fake.longitude())

        for field in fields:
            if field not in counter:
                counter[field] = {}
            counter[field].update({data[i][field]: counter[field].get(data[i][field], 0) + 1})

        if latmin <= data[i]['lat'] <= latmax:
            counter['lat'] = counter.get('lat', 0) + 1
        if lonmin < data[i]['lon'] < lonmax:
            counter['lon'] = counter.get('lon', 0) + 1
        if latmin <= data[i]['lat'] <= latmax and lonmin < data[i]['lon'] < lonmax:
            counter['geo'] = counter.get('geo', 0) + 1

    write_to_files(data, 'data.json', files_number)
    write_to_files(counter, 'analysis.json')
