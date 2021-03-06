/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.prestosql.orc;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public final class OrcDataSourceIdWithTimeStamp
{
    private final OrcDataSourceId id;
    private final long modifiedTime;

    public OrcDataSourceIdWithTimeStamp(OrcDataSourceId id, long modifiedTime)
    {
        this.id = requireNonNull(id, "id is null");
        this.modifiedTime = modifiedTime;
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
        OrcDataSourceIdWithTimeStamp that = (OrcDataSourceIdWithTimeStamp) o;
        return modifiedTime == that.modifiedTime && id.equals(that.id);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id, modifiedTime);
    }

    @Override
    public String toString()
    {
        return "OrcDataSourceIdWithTimeStamp{" +
                "id=" + id +
                ", modifiedTime=" + modifiedTime +
                '}';
    }
}
