/*
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
package io.prestosql.spi.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import static java.util.Objects.requireNonNull;

public class Symbol
        implements Comparable<Symbol>
{
    private final String name;

    @JsonCreator
    public Symbol(String name)
    {
        requireNonNull(name, "name is null");
        this.name = name;
    }

    @JsonValue
    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return name;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Symbol symbol = (Symbol) o;

        if (!name.equals(symbol.name)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        return name.hashCode();
    }

    @Override
    public int compareTo(Symbol o)
    {
        return name.compareTo(o.name);
    }
}
