% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
FROM airports
| EVAL geohash = ST_GEOHASH(location, 1)
| STATS
    count = COUNT(*),
    centroid = ST_CENTROID_AGG(location)
      BY geohash
| WHERE count >= 10
| EVAL geohashString = ST_GEOHASH_TO_STRING(geohash)
| KEEP count, centroid, geohashString
| SORT count DESC, geohashString ASC
```

| count:long | centroid:geo_point | geohashString:keyword |
| --- | --- | --- |
| 118 | POINT (-77.41857436454018 26.96522968734409) | d |
| 96 | POINT (23.181679135886952 27.295384635654045) | s |
| 94 | POINT (70.94076107503807 25.691916451026547) | t |
| 90 | POINT (-104.3941700803116 30.811849871650338) | 9 |
| 89 | POINT (18.71573683606942 53.165169130707305) | u |
| 85 | POINT (114.3722876966657 24.908398092505248) | w |
| 51 | POINT (-61.44522591713159 -22.87209844956284) | 6 |
| 38 | POINT (-9.429514887252529 25.497624435045413) | e |
| 34 | POINT (-111.8071846965262 52.464381378993174) | c |
| 30 | POINT (28.7045472683385 -14.706001980230212) | k |
| 28 | POINT (159.52750137208827 -25.555616633001982) | r |
| 22 | POINT (-4.410395708612421 54.90304926367985) | g |
| 21 | POINT (-69.40534970590046 50.93379438189523) | f |
| 17 | POINT (114.05526293222519 -10.898114638950895) | q |
| 16 | POINT (147.40052131412085 21.054660080408212) | x |
| 13 | POINT (63.64716878519035 54.37333276101317) | v |
| 12 | POINT (-39.53510569408536 -11.72166372067295) | 7 |


