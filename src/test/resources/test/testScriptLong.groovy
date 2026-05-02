//file:noinspection Annotator
//file:noinspection Annotator
//file:noinspection GrUnresolvedAccess
//file:noinspection GrUnresolvedAccess
//file:noinspection GrUnresolvedAccess
package test

import groovy.json.JsonBuilder
Thread.sleep(100_000)
def list = utils.find('serviceCall', [:], sp.limit(20)).collect{
  return [
    'title' : it.title,
    'UUID' : it.UUID,
    'number' : it.number
  ]
}
return new JsonBuilder(list).toString()