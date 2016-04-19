/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gaffer.function2.mock;

import gaffer.function2.Transformer;
import gaffer.tuple.tuplen.Tuple2;
import gaffer.tuple.tuplen.value.Value2;

public class MockTransform implements Transformer<Object, Tuple2<Object, Object>> {
    private Object output;

    public MockTransform() {}

    public MockTransform(Object output) {
        this.output = output;
    }

    public void setOutput(Object output) {
        this.output = output;
    }

    public Object getOutput() {
        return output;
    }

    @Override
    public Tuple2<Object, Object> execute(Object input) {
        Value2<Object, Object> out = new Value2<Object, Object>();
        out.put0(input);
        out.put1(output);
        return out;
    }

    @Override
    public MockTransform copy() {
        return new MockTransform(output);
    }
}