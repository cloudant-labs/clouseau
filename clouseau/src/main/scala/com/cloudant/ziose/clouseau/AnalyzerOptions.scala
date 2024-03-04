// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.clouseau

class AnalyzerOptions private (inner: Map[String, Any]) {
  def toMap = inner
}

object AnalyzerOptions {
  def fromMap(map: Map[_, _]) =
    new AnalyzerOptions(map.asInstanceOf[Map[String, Any]])
  def fromAnalyzerName(name: String) =
    new AnalyzerOptions(Map("name" -> name).asInstanceOf[Map[String, Any]])
  def fromKVsList(options: List[_]) = {
    // options can be a List of key-value pairs or a single String element wrapped in a List
    // the latter is a corner case which we should deprecate
    options match {
      case List(name: String) => new AnalyzerOptions(Map("name" -> name).asInstanceOf[Map[String, Any]])
      case list: List[_] => new AnalyzerOptions(collectKVs(list).toMap[String, Any].asInstanceOf[Map[String, Any]])
    }
  }
  def collectKVs(list: List[_]): List[(String, Any)] =
    list.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]]

  def from(options: Any): Option[AnalyzerOptions] =
    options match {
      case map: Map[_, _] => Some(fromMap(map))
      case list: List[_] => Some(fromKVsList(list))
      case string: String => Some(fromAnalyzerName(string))
      case _ => None
    }
}
