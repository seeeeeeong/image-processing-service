package com.realteeth.imagejob.exception

import java.util.UUID

class JobNotFoundException(jobId: UUID) :
    RuntimeException("Job not found: $jobId")

class InvalidStateTransitionException(message: String) :
    RuntimeException(message)

class DuplicateJobException(message: String) :
    RuntimeException(message)
