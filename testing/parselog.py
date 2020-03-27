#!/usr/bin/env python3
# www.jrodal.dev

import statistics
import sys

assert len(sys.argv) == 3


# not currently using, too powerful
def remove_outliers(data):
    q1, median, q3 = statistics.quantiles(data)
    lower_bound = q3 - 1.5 * (q3 - q1)
    upper_bound = q1 + 1.5 * (q3 - q1)
    return filter(lambda x: not (x < lower_bound or x > upper_bound), data)


# read latencies
with open(sys.argv[1]) as f:
    latencies = []
    cur_dic = {}
    _ = f.readline()  # read first marker
    while line:= f.readline():
        if " " not in line:
            latencies.append(sorted(cur_dic.values()))
            cur_dic.clear()
        else:
            info = line.strip().split()
            if len(info) == 3:
                cur_dic[info[0]] = int(info[1])
            elif len(info) == 2:
                cur_dic[info[0]] = int(info[1]) - cur_dic[info[0]]
            else:
                raise Exception("Invalid latencies provided")
    latencies.append(sorted(cur_dic.values()))

# write latency statistics
with open(sys.argv[2], "w") as f:
    print("distance","mean_lat","std",sep=",",file=f)
    for i, d in enumerate(latencies):
        print(i * 5, statistics.mean(d), statistics.stdev(d), sep=",", file=f)
